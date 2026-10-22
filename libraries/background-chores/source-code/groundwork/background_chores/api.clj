(ns groundwork.background-chores.api
  "Work that runs outside a request, in Postgres: a queue, workers, and
   schedules. The api of groundwork.background-chores.

     ;; a chore kind: a map, like a step
     (def send-invoice-email
       {:name         :app.mail/send-invoice-email
        :params-shape [:map [:order/id :int]]
        :run          (fn [chore-context params] …)   ; chore-context: :transaction :chore-id :attempt
        :timeout-ms   60000
        :attempts     5})

     ;; in a request's transaction: the chore exists only if that commits
     (background-chores/add-chore-to-queue! transaction send-invoice-email {:order/id 42})

     ;; on each server that runs chores
     (background-chores/start-workers! pool {:kinds     [send-invoice-email]
                                             :schedules [{:kind send-nightly-report :params {} :cron \"0 1 * * *\"}]
                                             :workers   4
                                             :poll-ms   1000})

   A chore is a row in groundwork.chores. Every :poll-ms the workers look for
   due chores; each free worker claims one (FOR UPDATE SKIP LOCKED, so no two
   servers run the same one) and runs its kind's :run in one transaction,
   within :timeout-ms. A chore that throws or runs out of time runs again
   later, waiting longer each time (30 s, 1 min, 2 min… up to an hour), until
   it has had its :attempts; then it is dead, kept with its last error. A
   chore that throws (make-give-up-exception …) is dead at once. A server that
   stops mid-chore leaves it running; it is queued again once its time limit
   and a minute have passed. A chore can run twice, so its work must be safe
   to repeat. Done chores are kept a week.

   A schedule puts its kind's chore in the queue at each time its cron line
   (UTC) allows; one server does it, whichever claims the schedule first."
  (:require
   [clojure.string :as str]
   [groundwork.background-chores.server-code.cron :as cron]
   [groundwork.sql.api :as sql]
   [groundwork.wire.api :as wire])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.time Instant OffsetDateTime ZoneOffset)
   (java.util.concurrent ArrayBlockingQueue Semaphore TimeUnit)))

(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork/background_chores/sql_files/" file-name)))

(def ^:private create-tables          (make-query "create_tables.sql"))
(def ^:private insert-chore             (make-query "insert_chore.sql"))
(def ^:private claim-next-chore         (make-query "claim_next_chore.sql"))
(def ^:private update-chore-to-done     (make-query "update_chore_to_done.sql"))
(def ^:private update-chore-to-failed   (make-query "update_chore_to_failed.sql"))
(def ^:private update-stuck-chores      (make-query "update_stuck_chores_to_queued.sql"))
(def ^:private delete-old-done-chores   (make-query "delete_old_done_chores.sql"))
(def ^:private insert-schedule        (make-query "insert_schedule.sql"))
(def ^:private find-due-schedules     (make-query "find_due_schedules.sql"))
(def ^:private update-schedule        (make-query "update_schedule_next_at.sql"))
(def ^:private find-chores-in-state     (make-query "find_chores_in_state.sql"))
(def ^:private update-chore-to-run-again (make-query "update_chore_to_run_again.sql"))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.background-chores"))

(defn- turn-instant-into-utc [^Instant instant]
  (OffsetDateTime/ofInstant instant ZoneOffset/UTC))

(defn- turn-kind-name-into-text [kind-name]
  (subs (str kind-name) 1))

;; -----------------------------------------------------------------------------
;; Tables

(defn create-tables!
  "Creates the schema groundwork and its tables chores and chore_schedules,
   unless they exist."
  [pool]
  (sql/run-statement! pool create-tables {}))

;; -----------------------------------------------------------------------------
;; Queueing

(defn add-chore-to-queue!
  "Saves a chore of a kind, on a pool or in a transaction (then it exists only
   if that commits). Its params must fit the kind's :params-shape. Options:
   :run-in-ms, run no sooner; :key, one chore of this key for as long as it is
   remembered: a second one queues nothing and returns the first one's id.
   Returns the chore's id."
  ([pool-or-transaction kind params] (add-chore-to-queue! pool-or-transaction kind params {}))
  ([pool-or-transaction {:keys [name params-shape timeout-ms attempts]} params {:keys [run-in-ms key]}]
   (when-not (wire/does-data-fit-shape? params-shape params)
     (throw (ex-info (str "Chore " name "'s params don't fit its shape")
                     {:type   :groundwork.background-chores/bad-params
                      :kind   name
                      :errors (wire/find-errors-in-data-for-shape params-shape params)})))
   (:chore/id (sql/find-one-row pool-or-transaction insert-chore
                              {:chore/kind         (turn-kind-name-into-text name)
                               :chore/params       (wire/turn-data-into-json-text
                                                  (wire/turn-shaped-data-into-json-data params-shape params))
                               :chore/run-at       (turn-instant-into-utc (.plusMillis (Instant/now) (or run-in-ms 0)))
                               :chore/max-attempts attempts
                               :chore/timeout-ms   timeout-ms
                               :chore/key          key}))))

(defn make-give-up-exception
  "The exception a chore's :run throws to end the chore for good, when no retry
   can fix it (the order it was for is gone). The chore is dead at once, kept
   with this message.

     (throw (background-chores/make-give-up-exception \"The order was deleted\" {:order/id id}))"
  ([message] (make-give-up-exception message {}))
  ([message data] (ex-info message (assoc data :type :groundwork.background-chores/give-up))))

(defn- does-failure-give-up? [throwable]
  (boolean (some #(= :groundwork.background-chores/give-up (:type (ex-data %)))
                 (take-while some? (iterate ex-cause throwable)))))

;; -----------------------------------------------------------------------------
;; Running one chore

(defn- find-wait-before-retry-ms [attempt]
  (min 3600000 (* 30000 (long (Math/pow 2 (dec attempt))))))

(defn- run-chore-and-catch
  "Runs the chore's kind in one transaction. Returns :ok or what was thrown."
  [pool kinds-by-name {:chore/keys [id kind params attempts]}]
  (try
    (let [{:keys [run params-shape] :as chore-kind} (get kinds-by-name kind)]
      (when-not chore-kind
        (throw (ex-info (str "No chore kind " kind " on this server")
                        {:type :groundwork.background-chores/unknown-kind :kind kind})))
      (let [{:keys [shaped-data errors]} (wire/turn-json-data-into-shaped-data-or-errors
                                          params-shape (wire/turn-json-text-into-data params))]
        (when errors
          (throw (ex-info "The chore's params don't fit its shape" {:errors errors})))
        (sql/run-in-transaction! pool
          (fn [transaction]
            (run {:transaction transaction :chore-id id :attempt attempts} shaped-data)))
        :ok))
    (catch Throwable throwable throwable)))

(defn- run-claimed-chore!
  "Runs a claimed chore within its time limit and saves what happened."
  [pool kinds-by-name {:chore/keys [id kind attempts timeout-ms] :as chore}]
  (let [outcome (promise)
        thread  (Thread/startVirtualThread #(deliver outcome (run-chore-and-catch pool kinds-by-name chore)))
        result  (deref outcome timeout-ms :timed-out)]
    (when (= :timed-out result) (.interrupt thread))
    (if (= :ok result)
      (sql/run-statement! pool update-chore-to-done {:chore/id id})
      (do
        (when (instance? Throwable result)
          (.log logger System$Logger$Level/WARNING (str "Chore " kind " " id " failed") ^Throwable result))
        (sql/run-statement! pool update-chore-to-failed
                            {:chore/id         id
                             :chore/retry-ms   (find-wait-before-retry-ms attempts)
                             :chore/for-good   (and (instance? Throwable result) (does-failure-give-up? result))
                             :chore/last-error (if (= :timed-out result)
                                               (str "Ran longer than " timeout-ms " ms")
                                               (str (.getName (class result)) ": " (ex-message result)))})))))

;; -----------------------------------------------------------------------------
;; The workers

(defn- make-schedule-key [{:keys [kind params cron]}]
  (str (turn-kind-name-into-text (:name kind)) " " cron " "
       (wire/turn-data-into-json-text (wire/turn-shaped-data-into-json-data (:params-shape kind) params))))

(defn- add-due-scheduled-chores!
  "Queues the chore of every due schedule this server claims, and moves the
   schedule to its next time."
  [pool schedules-by-key]
  (sql/run-in-transaction! pool
    (fn [transaction]
      (doseq [{key :chore-schedule/key} (sql/find-rows transaction find-due-schedules {})
              :let [{:keys [kind params cron]} (get schedules-by-key key)]
              :when kind]
        (add-chore-to-queue! transaction kind params)
        (sql/run-statement! transaction update-schedule
                            {:chore-schedule/key     key
                             :chore-schedule/next-at (turn-instant-into-utc
                                                    (cron/find-next-time-for-cron-line cron (Instant/now)))})))))

(defn- run-dispatcher!
  "Until stopped: queue due schedules, queue stuck chores again, and give
   every free worker a due chore; then wait :poll-ms, or less when a worker
   finishes or the workers stop."
  [{:keys [pool stopped ^Semaphore free-workers ^ArrayBlockingQueue wake kinds-by-name schedules-by-key poll-ms]}]
  (while (not @stopped)
    (try
      (add-due-scheduled-chores! pool schedules-by-key)
      (sql/run-statement! pool update-stuck-chores {})
      (loop []
        (when (and (not @stopped) (.tryAcquire free-workers))
          (if-let [chore (sql/find-one-row pool claim-next-chore {})]
            (do (Thread/startVirtualThread
                 (fn []
                   (try (run-claimed-chore! pool kinds-by-name (update chore :chore/kind keyword))
                        (catch Throwable e
                          (.log logger System$Logger$Level/ERROR "A chore's outcome couldn't be saved" e))
                        (finally
                          (.release free-workers)
                          (.offer wake :worker-free)))))
                (recur))
            (.release free-workers))))
      (catch Throwable e
        (when-not @stopped
          (.log logger System$Logger$Level/WARNING "The chore dispatcher failed; trying again" e))))
    (.poll wake (long poll-ms) TimeUnit/MILLISECONDS)))

(defn start-workers!
  "Starts the workers on this server. Settings:

     :kinds     [chore kinds]   every kind this server runs
     :schedules [{:kind a-chore-kind :params {…} :cron \"0 1 * * *\"}]
     :workers   4             chores running at once on this server
     :poll-ms   1000          how often to look for due chores

   Forgets done chores older than a week. Returns the running workers."
  [pool {:keys [kinds schedules workers poll-ms]}]
  (let [names (map :name kinds)]
    (when (not= (count names) (count (set names)))
      (throw (ex-info (str "Chore kinds named twice: " (str/join " " names))
                      {:type :groundwork.background-chores/kind-named-twice}))))
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
    (sql/run-statement! pool delete-old-done-chores {})
    (doseq [[key {:keys [cron]}] schedules-by-key]
      (sql/run-statement! pool insert-schedule
                          {:chore-schedule/key     key
                           :chore-schedule/next-at (turn-instant-into-utc
                                                  (cron/find-next-time-for-cron-line cron (Instant/now)))}))
    (assoc running :dispatcher (Thread/startVirtualThread #(run-dispatcher! running)))))

(defn stop-workers!
  "Stops taking chores, and waits for running ones to finish (at most 30 s)."
  [{:keys [stopped ^Thread dispatcher ^Semaphore free-workers ^ArrayBlockingQueue wake workers]}]
  (reset! stopped true)
  (.offer wake :stop)
  (.join dispatcher 5000)
  (when (.tryAcquire free-workers (int workers) 30 TimeUnit/SECONDS)
    (.release free-workers (int workers))))

;; -----------------------------------------------------------------------------
;; Looking at the queue

(defn list-queued-chores
  "Chores waiting, newest first, at most limit."
  [pool limit]
  (sql/find-rows pool find-chores-in-state {:chore/state "queued" :limit limit}))

(defn list-dead-chores
  "Chores that had all their attempts or gave up, with their last error,
   newest first, at most limit."
  [pool limit]
  (sql/find-rows pool find-chores-in-state {:chore/state "dead" :limit limit}))

(defn run-chore-again-now!
  "Queues a dead or waiting chore to run now, its attempts counted afresh.
   Whether there was such a chore."
  [pool chore-id]
  (some? (sql/find-one-row pool update-chore-to-run-again {:chore/id chore-id})))
