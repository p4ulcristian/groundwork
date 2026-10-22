(ns house.server.main.health
  "Two GETs for whoever runs the app (a deploy's proxy, a load balancer,
   a monitor), at the :health settings' paths, outside sessions, access and
   rate limits, each answered as JSON with the app's version:

     live   200 {\"status\":\"live\",\"version\":\"…\"} while the process
            answers; no database.
     ready  200 {\"status\":\"ready\",\"version\":\"…\"} when the app has
            started and the database answers a SELECT 1 within
            :database-timeout-ms; else 503
            {\"status\":\"not-ready\",\"version\":\"…\",\"reason\":\"…\"}.

   That the database matches the tables is not asked again: start-app!
   checked it (or migrated) before the app started, and a deploy migrating
   for the next version must not take the running one out of traffic.

   Checks that come while one is under way wait on that one, so a hung
   database holds one query, not one per check. Never cached (no-store)."
  (:require
   [cellar.api :as database]
   [wire.api :as wire]))

(def ^:private find-database-answer
  (database/make-query-from-sql-file "house/server/main/find_database_answer.sql"))

(defn make-health
  "What ready reads: the app's stage (:starting, :started, :stopping) and
   the database check under way."
  []
  {:stage (atom :starting) :database-check (atom nil)})

(defn mark-stage!
  "The app is now :started or :stopping."
  [health stage]
  (reset! (:stage health) stage))

(defn- find-or-start-database-check!
  "The check under way, else a new one: a future of :answered, or of the
   exception the query threw."
  [pool {:keys [database-check]}]
  (locking database-check
    (let [check @database-check]
      (if (and check (not (realized? check)))
        check
        (reset! database-check (future (try (database/find-one-row pool find-database-answer {})
                                            :answered
                                            (catch Exception e e))))))))

(defn- find-database-problem
  "Why the database isn't ready, or nil when it answered in time. The
   error goes to the app's log, :warn :house/database-not-ready, not to
   the asker."
  [log pool health timeout-ms]
  (let [result (deref (find-or-start-database-check! pool health) timeout-ms :timed-out)]
    (cond
      (= :answered result)  nil
      (= :timed-out result) (str "the database did not answer within " timeout-ms " ms")
      :else                 (do (log :warn :house/database-not-ready {:error result})
                                "the database answered with an error"))))

(defn- find-reason-not-ready [log pool health timeout-ms]
  (case @(:stage health)
    :starting "the app is starting"
    :stopping "the app is stopping"
    :started  (find-database-problem log pool health timeout-ms)))

(defn- answer-json [wire status body]
  {:status  status
   :headers {"content-type" "application/json" "cache-control" "no-store"}
   :body    (wire/turn-data-into-json-text wire body)})

(defn make-health-routes
  "The live and ready routes, at the :health settings' paths, as reitit
   data, answered as JSON by the app's wire; a database that answered
   with an error is written to the app's log."
  [wire log pool health {:keys [version] {:keys [live-path ready-path database-timeout-ms]} :health}]
  [[live-path  {:get {:handler (fn [_] (answer-json wire 200 {:status "live" :version version}))}}]
   [ready-path {:get {:handler (fn [_]
                                 (if-let [reason (find-reason-not-ready log pool health database-timeout-ms)]
                                   (answer-json wire 503 {:status "not-ready" :version version :reason reason})
                                   (answer-json wire 200 {:status "ready" :version version})))}}]])
