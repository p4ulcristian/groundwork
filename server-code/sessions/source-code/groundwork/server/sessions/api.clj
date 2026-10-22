(ns groundwork.server.sessions.api
  "Who is asking: a session in Postgres, a cookie of random bytes in the
   browser. The api of groundwork.server.sessions.

   The app's own login proves who someone is, then calls sign-in!; signing
   out deletes rows, so access ends at once on every server. A step at the
   start of each handler finds the session:

     (def find-session (sessions/make-find-session-step pool settings))
     {:orders/of-shop {:steps [find-session check-access find-orders add-answer]}}

   Settings: {:session-days 30 :allowed-origin \"https://ironrainbow.com\" :secure-cookie? true}.
   Its table is a map (sessions-table) the app passes to groundwork.server.database."
  (:require
   [groundwork.server.sessions.cookie :as cookie]
   [groundwork.server.sessions.origin :as origin]
   [groundwork.server.database.api :as database]
   [groundwork.server.steps.api :as steps])
  (:import
   (java.time Duration Instant OffsetDateTime ZoneOffset)))

(def sessions-table
  "The sessions, in Groundwork's own folder."
  {:table   :groundwork/sessions
   :columns [[:session/fingerprint :string       {:primary-key? true}]
             [:session/person-id   :uuid]
             [:session/created-at  :time/instant {:default :now}]
             [:session/ends-at     :time/instant]]
   :indexes [[:session/person-id] [:session/ends-at]]})

(defn- make-query [file-name]
  (database/make-query-from-sql-file (str "groundwork/server/sessions/" file-name)))

(def ^:private insert-session            (make-query "insert_session.sql"))
(def ^:private find-session              (make-query "find_session.sql"))
(def ^:private update-session-ends-at    (make-query "update_session_ends_at.sql"))
(def ^:private delete-session            (make-query "delete_session.sql"))
(def ^:private delete-sessions-of-person (make-query "delete_sessions_of_person.sql"))
(def ^:private delete-ended-sessions     (make-query "delete_ended_sessions.sql"))

(defn- find-ends-at [{:keys [session-days]}]
  (OffsetDateTime/ofInstant (.plus (Instant/now) (Duration/ofDays session-days)) ZoneOffset/UTC))

(defn sign-in!
  "Makes a session for a person whose login the app has proven. Returns
   {:set-cookie text} for the answer's Set-Cookie header."
  [pool person-id {:keys [session-days secure-cookie?] :as settings}]
  (let [cookie-value (cookie/make-cookie-value)]
    (database/run-statement! pool insert-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)
                                             :session/person-id   person-id
                                             :session/ends-at     (find-ends-at settings)})
    {:set-cookie (cookie/make-set-cookie-text cookie-value (* session-days 86400) secure-cookie?)}))

(defn sign-out!
  "Ends the request's session at once. Returns {:set-cookie text} that
   deletes the cookie."
  [pool request {:keys [secure-cookie?]}]
  (when-let [cookie-value (cookie/find-cookie-value request)]
    (database/run-statement! pool delete-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)}))
  {:set-cookie (cookie/make-set-cookie-text "" 0 secure-cookie?)})

(defn sign-out-everywhere!
  "Ends every session of a person at once (signing out everywhere, a
   removed member). Returns how many ended."
  [pool person-id]
  (database/run-statement! pool delete-sessions-of-person {:session/person-id person-id}))

(defn delete-ended-sessions!
  "Forgets sessions that have ended; for a nightly chore. Returns how many."
  [pool]
  (database/run-statement! pool delete-ended-sessions {}))

(defn- is-due-for-longer-life? [ends-at {:keys [session-days]}]
  ;; a session seen again is pushed out, at most once an hour
  (.isBefore (.toInstant ^OffsetDateTime ends-at)
             (.minus (.plus (Instant/now) (Duration/ofDays session-days)) (Duration/ofHours 1))))

(defn- find-person-id [pool request settings]
  (when-let [cookie-value (cookie/find-cookie-value request)]
    (let [fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)]
      (when-let [{:session/keys [person-id ends-at]} (database/find-one-row pool find-session {:session/fingerprint fingerprint})]
        (when (is-due-for-longer-life? ends-at settings)
          (database/run-statement! pool update-session-ends-at {:session/fingerprint fingerprint
                                                           :session/ends-at     (find-ends-at settings)}))
        person-id))))

(def ^:private not-from-the-app
  {:error "This request didn't come from the app's own pages."})

(defn make-find-session-step
  "A step for the start of a handler: a request that changes something must
   come from the app's own pages (its Origin), or it is answered with an
   error; then :groundwork.server.sessions/person-id is added to the context, nil
   when nobody is signed in. Reads the request at
   :groundwork.server.transport/request."
  [pool {:keys [allowed-origin] :as settings}]
  {:name  :groundwork.server.sessions/find-session
   :enter (fn [context]
            (let [request (:groundwork.server.transport/request context)]
              (if (origin/does-request-come-from-allowed-origin? request allowed-origin)
                (assoc context :groundwork.server.sessions/person-id (find-person-id pool request settings))
                (steps/add-answer-to-context context not-from-the-app))))})
