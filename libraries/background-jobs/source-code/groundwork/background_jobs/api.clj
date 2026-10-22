(ns groundwork.background-jobs.api
  "Work that runs outside a request, in Postgres: a queue, workers, and
   schedules. The api of groundwork.background-jobs.

     ;; a job kind: a map, like a step
     (def send-invoice-email
       {:name         :app.mail/send-invoice-email
        :params-shape [:map [:order/id :int]]
        :run          (fn [job-context params] …)   ; job-context: :transaction :job-id :attempt
        :timeout-ms   60000
        :attempts     5})

     ;; in a request's transaction: the job exists only if that commits
     (background-jobs/add-job-to-queue! transaction send-invoice-email {:order/id 42})

     ;; on each server that runs jobs
     (background-jobs/start-workers! pool {:kinds     [send-invoice-email]
                                           :schedules [{:kind send-nightly-report :params {} :cron \"0 1 * * *\"}]
                                           :workers   4
                                           :poll-ms   1000})

   A job is a row in groundwork.jobs. Every :poll-ms the workers look for
   due jobs; each free worker claims one (FOR UPDATE SKIP LOCKED, so no two
   servers run the same one) and runs its kind's :run in one transaction,
   within :timeout-ms. A job that throws or runs out of time runs again
   later, waiting longer each time (30 s, 1 min, 2 min… up to an hour), until
   it has had its :attempts; then it is dead, kept with its last error. A
   job that throws (make-give-up-exception …) is dead at once. A server that
   stops mid-job leaves it running; it is queued again once its time limit
   and a minute have passed. A job can run twice, so its work must be safe
   to repeat. Done jobs are kept a week.

   A schedule puts its kind's job in the queue at each time its cron line
   (UTC) allows; one server does it, whichever claims the schedule first."
  (:require
   [clojure.string :as str]
   [groundwork.background-jobs.server-code.cron :as cron]
   [groundwork.sql.api :as sql]
   [groundwork.wire.api :as wire])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.time Instant OffsetDateTime ZoneOffset)
   (java.util.concurrent ArrayBlockingQueue Semaphore TimeUnit)))

(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork/background_jobs/sql_files/" file-name)))

(def ^:private create-tables          (make-query "create_tables.sql"))
(def ^:private insert-job             (make-query "insert_job.sql"))
(def ^:private claim-next-job         (make-query "claim_next_job.sql"))
(def ^:private update-job-to-done     (make-query "update_job_to_done.sql"))
(def ^:private update-job-to-failed   (make-query "update_job_to_failed.sql"))
(def ^:private update-stuck-jobs      (make-query "update_stuck_jobs_to_queued.sql"))
(def ^:private delete-old-done-jobs   (make-query "delete_old_done_jobs.sql"))
(def ^:private insert-schedule        (make-query "insert_schedule.sql"))
(def ^:private find-due-schedules     (make-query "find_due_schedules.sql"))
(def ^:private update-schedule        (make-query "update_schedule_next_at.sql"))
(def ^:private find-jobs-in-state     (make-query "find_jobs_in_state.sql"))
(def ^:private update-job-to-run-again (make-query "update_job_to_run_again.sql"))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.background-jobs"))

(defn- turn-instant-into-utc [^Instant instant]
  (OffsetDateTime/ofInstant instant ZoneOffset/UTC))

(defn- turn-kind-name-into-text [kind-name]
  (subs (str kind-name) 1))

;; -----------------------------------------------------------------------------
;; Tables

(defn create-tables!
  "Creates the schema groundwork and its tables jobs and job_schedules,
   unless they exist."
  [pool]
  (sql/run-statement! pool create-tables {}))

;; -----------------------------------------------------------------------------
;; Queueing

(defn add-job-to-queue!
  "Saves a job of a kind, on a pool or in a transaction (then it exists only
   if that commits). Its params must fit the kind's :params-shape. Options:
   :run-in-ms, run no sooner; :key, one job of this key for as long as it is
   remembered: a second one queues nothing and returns the first one's id.
   Returns the job's id."
  ([pool-or-transaction kind params] (add-job-to-queue! pool-or-transaction kind params {}))
  ([pool-or-transaction {:keys [name params-shape timeout-ms attempts]} params {:keys [run-in-ms key]}]
   (when-not (wire/does-data-fit-shape? params-shape params)
     (throw (ex-info (str "Job " name "'s params don't fit its shape")
                     {:type   :groundwork.background-jobs/bad-params
                      :kind   name
                      :errors (wire/find-errors-in-data-for-shape params-shape params)})))
   (:job/id (sql/find-one-row pool-or-transaction insert-job
                              {:job/kind         (turn-kind-name-into-text name)
                               :job/params       (wire/turn-data-into-json-text
                                                  (wire/turn-shaped-data-into-json-data params-shape params))
                               :job/run-at       (turn-instant-into-utc (.plusMillis (Instant/now) (or run-in-ms 0)))
                               :job/max-attempts attempts
                               :job/timeout-ms   timeout-ms
                               :job/key          key}))))

(defn make-give-up-exception
  "The exception a job's :run throws to end the job for good, when no retry
   can fix it (the order it was for is gone). The job is dead at once, kept
   with this message.

     (throw (background-jobs/make-give-up-exception \"The order was deleted\" {:order/id id}))"
  ([message] (make-give-up-exception message {}))
  ([message data] (ex-info message (assoc data :type :groundwork.background-jobs/give-up))))

(defn- does-failure-give-up? [throwable]
  (boolean (some #(= :groundwork.background-jobs/give-up (:type (ex-data %)))
                 (take-while some? (iterate ex-cause throwable)))))

;; -----------------------------------------------------------------------------
;; Running one job

(defn- find-wait-before-retry-ms [attempt]
  (min 3600000 (* 30000 (long (Math/pow 2 (dec attempt))))))

(defn- run-job-and-catch
  "Runs the job's kind in one transaction. Returns :ok or what was thrown."
  [pool kinds-by-name {:job/keys [id kind params attempts]}]
  (try
    (let [{:keys [run params-shape] :as job-kind} (get kinds-by-name kind)]
      (when-not job-kind
        (throw (ex-info (str "No job kind " kind " on this server")
                        {:type :groundwork.background-jobs/unknown-kind :kind kind})))
      (let [{:keys [shaped-data errors]} (wire/turn-json-data-into-shaped-data-or-errors
                                          params-shape (wire/turn-json-text-into-data params))]
        (when errors
          (throw (ex-info "The job's params don't fit its shape" {:errors errors})))
        (sql/run-in-transaction! pool
          (fn [transaction]
            (run {:transaction transaction :job-id id :attempt attempts} shaped-data)))
        :ok))
    (catch Throwable throwable throwable)))

(defn- run-claimed-job!
  "Runs a claimed job within its time limit and saves what happened."
  [pool kinds-by-name {:job/keys [id kind attempts timeout-ms] :as job}]
  (let [outcome (promise)
        thread  (Thread/startVirtualThread #(deliver outcome (run-job-and-catch pool kinds-by-name job)))
        result  (deref outcome timeout-ms :timed-out)]
    (when (= :timed-out result) (.interrupt thread))
    (if (= :ok result)
      (sql/run-statement! pool update-job-to-done {:job/id id})
      (do
        (when (instance? Throwable result)
          (.log logger System$Logger$Level/WARNING (str "Job " kind " " id " failed") ^Throwable result))
        (sql/run-statement! pool update-job-to-failed
                            {:job/id         id
                             :job/retry-ms   (find-wait-before-retry-ms attempts)
                             :job/for-good   (and (instance? Throwable result) (does-failure-give-up? result))
                             :job/last-error (if (= :timed-out result)
                                               (str "Ran longer than " timeout-ms " ms")
                                               (str (.getName (class result)) ": " (ex-message result)))})))))

;; -----------------------------------------------------------------------------
;; The workers

(defn- make-schedule-key [{:keys [kind params cron]}]
  (str (turn-kind-name-into-text (:name kind)) " " cron " "
       (wire/turn-data-into-json-text (wire/turn-shaped-data-into-json-data (:params-shape kind) params))))

(defn- add-due-scheduled-jobs!
  "Queues the job of every due schedule this server claims, and moves the
   schedule to its next time."
  [pool schedules-by-key]
  (sql/run-in-transaction! pool
    (fn [transaction]
      (doseq [{key :job-schedule/key} (sql/find-rows transaction find-due-schedules {})
              :let [{:keys [kind params cron]} (get schedules-by-key key)]
              :when kind]
        (add-job-to-queue! transaction kind params)
        (sql/run-statement! transaction update-schedule
                            {:job-schedule/key     key
                             :job-schedule/next-at (turn-instant-into-utc
                                                    (cron/find-next-time-for-cron-line cron (Instant/now)))})))))

(defn- run-dispatcher!
  "Until stopped: queue due schedules, queue stuck jobs again, and give
   every free worker a due job; then wait :poll-ms, or less when a worker
   finishes or the workers stop."
  [{:keys [pool stopped ^Semaphore free-workers ^ArrayBlockingQueue wake kinds-by-name schedules-by-key poll-ms]}]
  (while (not @stopped)
    (try
      (add-due-scheduled-jobs! pool schedules-by-key)
      (sql/run-statement! pool update-stuck-jobs {})
      (loop []
        (when (and (not @stopped) (.tryAcquire free-workers))
          (if-let [job (sql/find-one-row pool claim-next-job {})]
            (do (Thread/startVirtualThread
                 (fn []
                   (try (run-claimed-job! pool kinds-by-name (update job :job/kind keyword))
                        (catch Throwable e
                          (.log logger System$Logger$Level/ERROR "A job's outcome couldn't be saved" e))
                        (finally
                          (.release free-workers)
                          (.offer wake :worker-free)))))
                (recur))
            (.release free-workers))))
      (catch Throwable e
        (when-not @stopped
          (.log logger System$Logger$Level/WARNING "The job dispatcher failed; trying again" e))))
    (.poll wake (long poll-ms) TimeUnit/MILLISECONDS)))

(defn start-workers!
  "Starts the workers on this server. Settings:

     :kinds     [job kinds]   every kind this server runs
     :schedules [{:kind a-job-kind :params {…} :cron \"0 1 * * *\"}]
     :workers   4             jobs running at once on this server
     :poll-ms   1000          how often to look for due jobs

   Forgets done jobs older than a week. Returns the running workers."
  [pool {:keys [kinds schedules workers poll-ms]}]
  (let [names (map :name kinds)]
    (when (not= (count names) (count (set names)))
      (throw (ex-info (str "Job kinds named twice: " (str/join " " names))
                      {:type :groundwork.background-jobs/kind-named-twice}))))
  (doseq [{:keys [cron]} schedules]
    (cron/turn-cron-line-into-allowed-values cron))
  (let [schedules-by-key (into {} (map (juxt make-schedule-key identity)) schedules)
        running          {:pool             pool
                          :kinds-by-name    (into {} (map (juxt :name identity)) kinds)
                          :schedules-by-key schedules-by-key
                          :poll-ms          poll-ms
                          :workers          workers
                          :stopped          (atom false)
                          :free-workers     (Semaphore. (int workers))
                          :wake             (ArrayBlockingQueue. 1)}]
    (sql/run-statement! pool delete-old-done-jobs {})
    (doseq [[key {:keys [cron]}] schedules-by-key]
      (sql/run-statement! pool insert-schedule
                          {:job-schedule/key     key
                           :job-schedule/next-at (turn-instant-into-utc
                                                  (cron/find-next-time-for-cron-line cron (Instant/now)))}))
    (assoc running :dispatcher (Thread/startVirtualThread #(run-dispatcher! running)))))

(defn stop-workers!
  "Stops taking jobs, and waits for running ones to finish (at most 30 s)."
  [{:keys [stopped ^Thread dispatcher ^Semaphore free-workers ^ArrayBlockingQueue wake workers]}]
  (reset! stopped true)
  (.offer wake :stop)
  (.join dispatcher 5000)
  (when (.tryAcquire free-workers (int workers) 30 TimeUnit/SECONDS)
    (.release free-workers (int workers))))

;; -----------------------------------------------------------------------------
;; Looking at the queue

(defn list-queued-jobs
  "Jobs waiting, newest first, at most limit."
  [pool limit]
  (sql/find-rows pool find-jobs-in-state {:job/state "queued" :limit limit}))

(defn list-dead-jobs
  "Jobs that had all their attempts or gave up, with their last error,
   newest first, at most limit."
  [pool limit]
  (sql/find-rows pool find-jobs-in-state {:job/state "dead" :limit limit}))

(defn run-job-again-now!
  "Queues a dead or waiting job to run now, its attempts counted afresh.
   Whether there was such a job."
  [pool job-id]
  (some? (sql/find-one-row pool update-job-to-run-again {:job/id job-id})))
