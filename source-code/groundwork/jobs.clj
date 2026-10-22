(ns groundwork.jobs
  "Background jobs, in Postgres: work that shouldn't happen inside a request
   (an email after an order, a PDF, a retried webhook) and work on a clock.

     :jobs {:kinds     [app.mail/jobs app.reports/jobs]   ; maps gathered, like endpoints
            :schedules [{:job :app.reminders/send :params {} :cron \"0 18 * * *\"}]
            :workers   4        ; jobs running at once on this server
            :poll-ms   5000     ; how often to look when nothing wakes the workers
            :on?       true}    ; false on previews: nothing runs, jobs wait
     ;; or nil for no jobs

     ;; a job kind
     {:app.mail/send {:run        (fn [ctx params] …)   ; ctx: :db (its transaction), :job, :attempt, :jobs
                      :params     [:map [:order/id :uuid]]
                      :timeout-ms 60000
                      :attempts   5
                      :runs-in    [:order :order/id]}}  ; optional, see below

     ;; in an endpoint's handler (or another job's): only there if it commits
     (jobs/enqueue! ctx :app.mail/send {:order/id id})
     (jobs/enqueue! ctx :app.mail/send {:order/id id} {:in-ms 60000})

   A job is a row in groundwork.jobs, written in the app's own transaction:
   it exists only if that commits, and the workers hear about it then
   (NOTIFY), or within :poll-ms. Each worker claims a due job with FOR UPDATE
   SKIP LOCKED, so no two servers run the same one, and runs it on a virtual
   thread within its :timeout-ms. A job that throws or runs out of time is
   tried again later, waiting longer each time (30 s, 1 min, 2 min… up to an
   hour), until it has had its :attempts; then it is dead, kept with its last
   error, and retry! brings it back. A server that stops mid-job leaves it
   running; it is queued again once its time limit and a minute have passed.
   Since a job can run twice, its work must be safe to repeat.

   A job runs in one transaction behind the database wall as the system
   (every row), unless its kind says where it runs, :runs-in [kind param]: then the wall
   shows only that scope, the one named by the param (a scope's id), or the
   scope of the record it names.

   Schedules are cron lines in UTC (minute hour day-of-month month
   day-of-week; *, numbers, lists a,b, ranges a-b, steps */n). Each due
   schedule is claimed by one server, which queues its job and moves it on."
  (:require
   [clojure.string :as str]
   [groundwork.endpoints.wire :as wire]
   [groundwork.permissions.check :as check]
   [groundwork.postgres :as pg]
   [groundwork.settings :as settings])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.sql Connection)
   (java.time Instant LocalDateTime OffsetDateTime ZoneOffset)
   (java.time.temporal ChronoUnit)
   (java.util.concurrent ArrayBlockingQueue Semaphore TimeUnit)
   (org.postgresql PGConnection)))

(pg/defsql insert-job "groundwork/jobs/insert.sql")
(pg/defsql claim "groundwork/jobs/claim.sql")
(pg/defsql update-to-done "groundwork/jobs/update_to_done.sql")
(pg/defsql failed "groundwork/jobs/failed.sql")
(pg/defsql update-stuck-to-queued "groundwork/jobs/update_stuck_to_queued.sql")
(pg/defsql delete-done "groundwork/jobs/delete_done.sql")
(pg/defsql insert-schedule "groundwork/jobs/insert_schedule.sql")
(pg/defsql due-schedules "groundwork/jobs/due_schedules.sql")
(pg/defsql update-schedule "groundwork/jobs/update_schedule.sql")
(pg/defsql by-state "groundwork/jobs/by_state.sql")
(pg/defsql retry "groundwork/jobs/retry.sql")

(def ^:private ^System$Logger logger (System/getLogger "groundwork.jobs"))

;; =============================================================================
;; Cron
;; =============================================================================

(defn- field-values
  "The values a cron field allows, between lo and hi: *, n, a-b, each
   optionally /step, comma-separated."
  [field lo hi]
  (set (mapcat (fn [part]
                 (let [[span step] (str/split part #"/" 2)
                       step        (if step (parse-long step) 1)
                       [a b]       (cond
                                     (= "*" span)              [lo hi]
                                     (str/includes? span "-") (mapv parse-long (str/split span #"-" 2))
                                     :else                     (let [n (parse-long span)]
                                                                 [n (if (str/includes? part "/") hi n)]))]
                   (when-not (and a b step (<= lo a b hi) (pos? step))
                     (throw (ex-info (str "Not a cron field: " field) {:type ::bad-cron :field field})))
                   (range a (inc b) step)))
               (str/split field #","))))

(defn parse-cron
  "\"0 18 * * *\" → the allowed minutes, hours, days, months, weekdays."
  [line]
  (let [[mi h dom mo dow :as fields] (str/split (str/trim line) #"\s+")]
    (when-not (= 5 (count fields))
      (throw (ex-info (str "A cron line has five fields: " line) {:type ::bad-cron :line line})))
    {:minutes  (field-values mi 0 59)
     :hours    (field-values h 0 23)
     :days     (field-values dom 1 31)
     :months   (field-values mo 1 12)
     :weekdays (set (map #(mod % 7) (field-values dow 0 7)))
     :any-day? (= "*" dom)
     :any-dow? (= "*" dow)}))

(defn next-time
  "The first minute after `after` (an Instant) that a cron line allows, UTC."
  ^Instant [line ^Instant after]
  (let [{:keys [minutes hours days months weekdays any-day? any-dow?]} (parse-cron line)
        day-ok? (fn [^LocalDateTime t]
                  (let [dom? (contains? days (.getDayOfMonth t))
                        dow? (contains? weekdays (mod (.getValue (.getDayOfWeek t)) 7))]
                    (cond (and any-day? any-dow?) true
                          any-day?                dow?
                          any-dow?                dom?
                          :else                   (or dom? dow?))))]
    (loop [t (.plusMinutes (.truncatedTo (LocalDateTime/ofInstant after ZoneOffset/UTC) ChronoUnit/MINUTES) 1)
           n 0]
      (cond
        (> n 600000)
        (throw (ex-info (str "This cron line never comes: " line) {:type ::bad-cron :line line}))

        (not (contains? months (.getMonthValue t)))
        (recur (.withDayOfMonth (.plusMonths (.truncatedTo t ChronoUnit/DAYS) 1) 1) (inc n))

        (not (day-ok? t))
        (recur (.plusDays (.truncatedTo t ChronoUnit/DAYS) 1) (inc n))

        (not (contains? hours (.getHour t)))
        (recur (.plusHours (.truncatedTo t ChronoUnit/HOURS) 1) (inc n))

        (not (contains? minutes (.getMinute t)))
        (recur (.plusMinutes t 1) (inc n))

        :else
        (.toInstant t ZoneOffset/UTC)))))

;; =============================================================================
;; Queueing
;; =============================================================================

(defn- kind-of [{:keys [kinds]} kind]
  (or (get kinds kind)
      (throw (ex-info (str "No job kind " kind) {:type ::unknown-kind :kind kind}))))

(defn enqueue!
  "Queue a job in the transaction of ctx (an endpoint's or a job's): it
   exists only if that commits. Options: :in-ms, run no sooner; :key, one
   job of this key for as long as it is remembered (done jobs are kept a
   week): a second enqueue queues nothing and returns the first one's id.
   Returns its id."
  ([ctx kind params] (enqueue! ctx kind params {}))
  ([{:keys [db jobs]} kind params {:keys [in-ms key]}]
   (when-not jobs
     (throw (ex-info "This app has no :jobs" {:type ::no-jobs})))
   (let [{:keys [params-shape timeout-ms attempts]} (kind-of jobs kind)]
     (when-not (wire/fits? params-shape params)
       (throw (ex-info (str "Job " kind "'s params don't fit its shape")
                       {:type ::bad-params :kind kind :errors (wire/explain params-shape params)})))
     (:job/id (pg/row db insert-job {:job/kind         (wire/endpoint-name kind)
                                     :job/params       (wire/write-json (wire/encode params-shape params))
                                     :job/run-at       (OffsetDateTime/ofInstant (.plusMillis (Instant/now) (or in-ms 0))
                                                                                 ZoneOffset/UTC)
                                     :job/max-attempts attempts
                                     :job/timeout-ms   timeout-ms
                                     :job/key          key})))))

;; =============================================================================
;; Running
;; =============================================================================

(defn- retry-ms [attempt]
  (min 3600000 (* 30000 (long (Math/pow 2 (dec attempt))))))

(defn- scopes-for [{:keys [perms]} [kind param] params]
  (let [id (get params param)]
    (if (contains? (get-in perms [:description :scopes]) kind)
      #{id}
      (check/scopes-of perms [kind id]))))

(defn- run-in-transaction
  "The job's handler in one transaction behind the wall."
  [{:keys [perms] :as part} {:keys [run runs-in]} ctx params]
  (let [go (fn [tx] (run (assoc ctx :db tx :jobs part) params))]
    (if runs-in
      (check/in-scopes perms (scopes-for part runs-in params) go)
      (check/as-system perms go))))

(defn give-up
  "The exception a job's work throws to end the job for good: no retry can
   fix it (the record it was for is gone, the address doesn't exist). The
   job is dead at once, kept with this message.

     (throw (jobs/give-up \"The order was deleted\" {:order/id id}))"
  ([message] (give-up message {}))
  ([message data] (ex-info message (assoc data :type ::give-up))))

(defn- gave-up?
  "Whether a failure, or what caused it, is a give-up."
  [e]
  (boolean (some #(= ::give-up (:type (ex-data %)))
                 (take-while some? (iterate ex-cause e)))))

(defn run-one!
  "Run one claimed job; record what happened. Returns :done, :queued or :dead."
  [{:keys [db] :as part} {:job/keys [id kind params attempts timeout-ms]}]
  (let [k       (keyword kind)
        entry   (get-in part [:kinds k])
        outcome (promise)
        thread  (Thread/startVirtualThread
                 (fn []
                   (deliver outcome
                            (try
                              (when-not entry
                                (throw (ex-info (str "No job kind " k " on this server") {:type ::unknown-kind})))
                              (let [{decoded :value errors :errors} (wire/decode (:params-shape entry)
                                                                                 (wire/read-json params))]
                                (when errors
                                  (throw (ex-info "The job's params don't fit its shape" {:errors errors})))
                                (run-in-transaction part entry {:job id :attempt attempts} decoded)
                                :ok)
                              (catch Throwable e e)))))
        result  (deref outcome timeout-ms ::timeout)]
    (when (= ::timeout result) (.interrupt thread))
    (if (= :ok result)
      (do (pg/execute! db update-to-done {:job/id (str id)}) :done)
      (let [message (if (= ::timeout result)
                      (str "Ran longer than " timeout-ms " ms")
                      (str (.getName (class result)) ": " (ex-message result)))]
        (when (instance? Throwable result)
          (.log logger System$Logger$Level/WARNING (str "Job " kind " " id " failed") ^Throwable result))
        (keyword (:job/state (pg/row db failed {:job/id         (str id)
                                                :job/retry-ms   (retry-ms attempts)
                                                :job/for-good   (and (instance? Throwable result)
                                                                     (gave-up? result))
                                                :job/last-error message})))))))

(defn- run-schedules!
  "Queue every due schedule's job, once across servers."
  [{:keys [db schedules] :as part}]
  (pg/with-transaction [tx db]
    (doseq [{key :job-schedule/key} (pg/rows tx due-schedules)
            :let [{:keys [job params cron]} (get schedules key)]
            :when job]
      (enqueue! {:db tx :jobs part} job params)
      (pg/execute! tx update-schedule {:job-schedule/key     key
                                       :job-schedule/next-at (OffsetDateTime/ofInstant (next-time cron (Instant/now))
                                                                                       ZoneOffset/UTC)}))))

(defn- dispatch!
  "Until stopped: queue due schedules, put stuck jobs back, and give every
   free worker a due job; then wait for a wake-up or :poll-ms."
  [{:keys [db stopped ^Semaphore free ^ArrayBlockingQueue wake settings] :as part}]
  (while (not @stopped)
    (try
      (run-schedules! part)
      (pg/execute! db update-stuck-to-queued)
      (loop []
        (when (and (not @stopped) (.tryAcquire free))
          (if-let [job (pg/row db claim)]
            (do (Thread/startVirtualThread
                 (fn []
                   (try (run-one! part job)
                        (catch Throwable e
                          (.log logger System$Logger$Level/ERROR "A job's outcome couldn't be recorded" e))
                        (finally
                          (.release free)
                          (.offer wake :done)))))
                (recur))
            (.release free))))
      (catch Throwable e
        (when-not @stopped
          (.log logger System$Logger$Level/WARNING "The job dispatcher failed; trying again" e))))
    (try (.poll wake (:poll-ms settings) TimeUnit/MILLISECONDS)
         (catch InterruptedException _ nil))))

(defn- listening-connection
  "A connection of the jobs' own, outside the pool, with the :db's TLS."
  ^Connection [db-settings]
  (doto (pg/connect db-settings {"ApplicationName" "groundwork-jobs"})
    (-> (.createStatement) (.execute "LISTEN groundwork_jobs"))))

(defn- listen!
  "Until stopped: wake the dispatcher when a job is queued. Without the
   connection, polling still finds the jobs."
  [{:keys [db-settings stopped ^ArrayBlockingQueue wake]}]
  (loop [conn nil]
    (when-not @stopped
      (let [conn (or conn (try (listening-connection db-settings)
                               (catch Exception e
                                 (.log logger System$Logger$Level/WARNING "Can't listen for jobs" e)
                                 (Thread/sleep 5000)
                                 nil)))]
        (recur (when conn
                 (try
                   (when (seq (.getNotifications (.unwrap conn PGConnection) 1000))
                     (.offer wake :queued))
                   conn
                   (catch Exception _
                     (try (.close conn) (catch Exception _ nil))
                     nil))))))))

;; =============================================================================
;; The REPL
;; =============================================================================

(defn queued
  "Jobs waiting, newest first."
  ([part] (queued part 50))
  ([{:keys [db]} n] (pg/rows db by-state {:job/state "queued" :limit n})))

(defn dead
  "Jobs that had all their attempts, with their last error, newest first."
  ([part] (dead part 50))
  ([{:keys [db]} n] (pg/rows db by-state {:job/state "dead" :limit n})))

(defn retry!
  "Run a dead (or waiting) job again now, its attempts counted afresh."
  [{:keys [db]} id]
  (some? (pg/row db retry {:job/id (str id)})))

;; =============================================================================
;; The part
;; =============================================================================

(defn- gather [maps]
  (reduce (fn [all m]
            (when-let [twice (seq (filter #(contains? all %) (keys m)))]
              (throw (ex-info (str "Job kinds named twice: " (str/join " " twice)) {:type ::bad-kind :kinds (vec twice)})))
            (merge all m))
          {}
          maps))

(defn- schedule-key [{:keys [job params cron]}]
  (str (wire/endpoint-name job) " " cron " " (wire/write-json params)))

(defn start
  "The jobs part: the kinds checked, the schedules known, and on a server
   that runs jobs, the dispatcher and its wake-up listener."
  [{:keys [settings db permissions app-settings]}]
  (settings/check-part! :jobs settings)
  (if (nil? settings)
    nil
    (let [kinds     (into {}
                          (map (fn [[k {:keys [params] :as entry}]]
                                 [k (assoc entry :params-shape (wire/schema params))]))
                          (gather (:kinds settings)))
          schedules (into {} (map (juxt schedule-key identity)) (:schedules settings))
          _         (doseq [{:keys [job cron]} (:schedules settings)]
                      (when-not (contains? kinds job)
                        (throw (ex-info (str "A schedule names an unknown job kind " job) {:type ::unknown-kind :kind job})))
                      (parse-cron cron))
          part      {:db          db
                     :db-settings (:db app-settings)
                     :perms       permissions
                     :settings    settings
                     :kinds       kinds
                     :schedules   schedules
                     :stopped     (atom false)
                     :free        (Semaphore. (int (:workers settings)))
                     :wake        (ArrayBlockingQueue. 1)}]
      (pg/execute! db delete-done)
      (doseq [[key {:keys [cron]}] schedules]
        (pg/execute! db insert-schedule {:job-schedule/key     key
                                         :job-schedule/next-at (OffsetDateTime/ofInstant (next-time cron (Instant/now))
                                                                                         ZoneOffset/UTC)}))
      (if (:on? settings)
        (assoc part
               :dispatcher (Thread/startVirtualThread #(dispatch! part))
               :listener   (Thread/startVirtualThread #(listen! part)))
        part))))

(defn stop
  "Stop taking jobs; let running ones finish (up to their time limits, at
   most 30 s here)."
  [{:keys [stopped ^Thread dispatcher ^Thread listener ^Semaphore free ^ArrayBlockingQueue wake settings]}]
  (when stopped
    (reset! stopped true)
    (some-> wake (.offer :stop))
    (some-> dispatcher (.join 5000))
    (some-> listener (.join 3000))
    (when free
      (when (.tryAcquire free (int (:workers settings)) 30 TimeUnit/SECONDS)
        (.release free (int (:workers settings)))))))
