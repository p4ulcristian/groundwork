(ns groundwork.server.sync.api
  "Keeping what screens show in step with the database. The api of
   groundwork.server.sync; the browser's side is groundwork.browser.sync.

   1. Every table map says how its changes reach screens (:sync :by-access,
      :everyone or [:silent \"why\"]); put-sync-on-tables checks that and
      gives the tables that tell their triggers, which call one Postgres
      function (install-sync-function!) after every statement.
   2. Postgres tells every listening server, when a transaction commits,
      which rows of which table changed. start-sync! listens, and tells each
      open sse line whose person may read one of the rows:

        {:sync/changed {:table \"orders\" :ids [\"…\"]}}

      After a break in listening, every line is told {:sync/missed {}}:
      something may have changed unheard.
   3. Every answer says the tables it was read from (put-sync-into-handlers),
      so the browser asks again only the questions a change touches.

   The server invalidates nothing; the browser decides what to ask again.

     (sync/install-sync-function! owner-pool)
     (database/migrate! owner-pool (sync/put-sync-on-tables tables scope-kinds) changes)
     (def sync (sync/start-sync! pool {:tables tables :scope-kinds scope-kinds :find-session-step find-session}))
     ;; transport's :on-sse-open #(sync/add-sse-line! sync %), :on-sse-close #(sync/remove-sse-line! sync %)"
  (:require
   [groundwork.server.access.api :as access]
   [cellar.api :as database]
   [groundwork.server.steps.api :as steps]
   [groundwork.server.sync.tables :as tables]
   [groundwork.server.sync.telling :as telling]
   [groundwork.server.transport.api :as transport]))

(def ^:private create-sync-function
  (database/make-query-from-sql-file "groundwork/server/sync/create_sync_function.sql"))

(defn install-sync-function!
  "Installs, or replaces, groundwork.tell_sync(), which the tables' triggers
   call. Run it before migrate: Postgres refuses a trigger whose function
   doesn't exist yet."
  [pool]
  (database/run-statement! pool create-sync-function {}))

(defn check-sync-of-tables
  "Throws with every problem at once: a table that says nothing about sync,
   :by-access without :access sentences, [:silent] without a reason."
  [tables]
  (tables/check-sync-of-tables tables))

(defn put-sync-on-tables
  "The table maps, checked (check-sync-of-tables), each that tells given
   :on-change: the triggers cellar makes.

     (database/migrate! owner-pool (sync/put-sync-on-tables tables scope-kinds) changes)"
  [tables scope-kinds]
  (tables/put-on-change-on-tables tables (access/list-readers-of-tables tables scope-kinds)))

(defn- turn-key-into-text [k] (subs (str k) 1))

(defn- turn-tables-into-notes [tables]
  {:tables (if (= :unknown tables) "any" (vec (sort (map turn-key-into-text tables))))})

(defn- run-and-note-tables [run context]
  (let [[context tables] (database/run-and-list-tables-read #(run context))]
    (assoc context :groundwork.server.transport/notes (turn-tables-into-notes tables))))

(defn put-sync-into-handlers
  "Every handler's answer gets a note of the tables its steps read,
   {:tables [\"orders\" \"shops\"]}, or {:tables \"any\"} when Postgres's planner
   couldn't say for one of its queries; transport sends it beside the answer.
   Only the steps count, not what runs around them (the session, who holds
   what): put it in after groundwork.server.access."
  [handlers]
  (update-vals handlers
               (fn [{:keys [run-steps-in] :as handler}]
                 (let [inner (or run-steps-in (fn [run context] (run context)))]
                   (assoc handler :run-steps-in
                          (fn [run context]
                            (inner #(run-and-note-tables run %) context)))))))

;; -----------------------------------------------------------------------------
;; Lines and listening

(defn- find-person-id [find-session-step request]
  (let [context (steps/run-steps-until-answered [find-session-step] {:groundwork.server.transport/request request})]
    (when-not (steps/does-context-have-answer? context)
      (:groundwork.server.sessions/person-id context))))

(defn add-sse-line!
  "Keeps an sse line that has just opened, with the person its session
   names (nil for a visitor), to tell of changes. For transport's :on-sse-open."
  [{:keys [lines find-session-step]} line]
  (swap! lines assoc (:groundwork.server.transport/channel line)
         {:line line :person-id (find-person-id find-session-step (:groundwork.server.transport/request line))}))

(defn remove-sse-line!
  "Forgets a line that has closed. For transport's :on-sse-close."
  [{:keys [lines]} line]
  (swap! lines dissoc (:groundwork.server.transport/channel line)))

(defn- tell-line! [{:keys [line]} message]
  (transport/send-message-on-sse-line! line message))

(defn- tell-of-notifications! [{:keys [pool rules lines]} notifications]
  (let [open (vals @lines)]
    (doseq [change (telling/merge-changes (map (comp telling/turn-payload-into-change :payload) notifications))
            :let [rule (get rules (:table change))]
            :when rule
            told (telling/find-told-lines pool rule change open)]
      (tell-line! told (telling/make-message change)))))

(defn- tell-everyone-of-missed! [{:keys [lines]}]
  (doseq [told (vals @lines)]
    (tell-line! told {:sync/missed {}})))

(defn start-sync!
  "Listens for changes and tells the open lines. Setup: :tables (the app's
   table maps), :scope-kinds, :find-session-step (from groundwork.server.sessions).
   Holds one connection of the pool. Returns the sync, for add-sse-line!,
   remove-sse-line! and stop-sync!."
  [pool {:keys [tables scope-kinds find-session-step]}]
  (tables/check-sync-of-tables tables)
  (let [readers (access/list-readers-of-tables tables scope-kinds)
        sync    {:pool              pool
                 :lines             (atom {})
                 :find-session-step find-session-step
                 :rules             (into {} (for [{:keys [table sync]} tables
                                                   :when (tables/is-table-told? {:sync sync})]
                                               [table (if (= :everyone sync) :everyone (get readers table))]))}]
    (assoc sync :listener
           (database/start-listening! pool "groundwork_sync"
                                      {:on-notifications #(tell-of-notifications! sync %)
                                       :on-reconnect     #(tell-everyone-of-missed! sync)}))))

(defn stop-sync!
  "Stops listening; the lines stay open until the server stops."
  [{:keys [listener]}]
  (database/stop-listening! listener))

(defn count-open-lines
  "How many sse lines are kept, for tests and a status page."
  [{:keys [lines]}]
  (count @lines))
