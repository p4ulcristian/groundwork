(ns groundwork.server.sync.api
  "Keeping what screens show in step with the database. The api of
   groundwork.server.sync; the browser's side is groundwork.browser.sync.

   1. Every table map says whether its changes reach screens (:sync :tell or
      [:silent \"why\"]); put-sync-on-tables checks that and gives the
      tables that tell their triggers, which call one Postgres function
      (install-sync-function!) after every statement.
   2. Postgres tells every listening server, when a transaction commits,
      which table changed: no ids, no values. start-sync! listens, and tells
      every open sse line, once per table in what it gathered:

        {:sync/changed {:table \"orders\"}}

      After a break in listening, every line is told {:sync/missed {}}:
      something may have changed unheard.
   3. Every answer says the tables it was read from (put-sync-into-handlers),
      so the browser asks again only the questions a change touches; the
      gates decide what each asker gets. Sync decides no one's access.

     (sync/install-sync-function! owner-pool)
     (database/migrate! owner-pool (sync/put-sync-on-tables tables) changes)
     (def sync (sync/start-sync! pool))
     ;; mailroom's :on-sse-open #(sync/add-sse-line! sync %), :on-sse-close #(sync/remove-sse-line! sync %)"
  (:require
   [cellar.api :as database]
   [mailroom.api :as mailroom]
   [groundwork.server.sync.tables :as tables]
   [groundwork.server.sync.telling :as telling]))

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
   [:silent] without a reason, a value that is neither."
  [tables]
  (tables/check-sync-of-tables tables))

(defn put-sync-on-tables
  "The table maps, checked (check-sync-of-tables), each that tells given
   :on-change: the triggers cellar makes.

     (database/migrate! owner-pool (sync/put-sync-on-tables tables) changes)"
  [tables]
  (tables/put-on-change-on-tables tables))

(defn- turn-key-into-text [k] (subs (str k) 1))

(defn- turn-tables-into-notes [tables]
  {:tables (if (= :unknown tables) "any" (vec (sort (map turn-key-into-text tables))))})

(defn- run-and-note-tables [run context]
  (let [[context tables] (database/run-and-list-tables-read #(run context))]
    (assoc context :mailroom/notes (turn-tables-into-notes tables))))

(defn put-sync-into-handlers
  "Every handler's answer gets a note of the tables its steps read,
   {:tables [\"orders\" \"shops\"]}, or {:tables \"any\"} when Postgres's planner
   couldn't say for one of its queries; mailroom sends it beside the answer.
   Only the steps count, not what runs around them (the session, who holds
   what): put it in after doorman's put-access-into-handlers."
  [handlers]
  (update-vals handlers
               (fn [{:keys [run-steps-in] :as handler}]
                 (let [inner (or run-steps-in (fn [run context] (run context)))]
                   (assoc handler :run-steps-in
                          (fn [run context]
                            (inner #(run-and-note-tables run %) context)))))))

;; -----------------------------------------------------------------------------
;; Lines and listening

(defn add-sse-line!
  "Keeps an sse line that has just opened, to tell of changes. For
   mailroom's :on-sse-open."
  [{:keys [lines]} line]
  (swap! lines assoc (:mailroom/channel line) line))

(defn remove-sse-line!
  "Forgets a line that has closed. For mailroom's :on-sse-close."
  [{:keys [lines]} line]
  (swap! lines dissoc (:mailroom/channel line)))

(defn- tell-every-line! [{:keys [lines]} message]
  (doseq [line (vals @lines)]
    (mailroom/send-message-on-sse-line! line message)))

(defn- tell-of-notifications! [sync notifications]
  (doseq [table (telling/list-changed-tables (map :payload notifications))]
    (tell-every-line! sync (telling/make-message table))))

(defn start-sync!
  "Listens for changes and tells every open line. Holds one connection of
   the pool. Returns the sync, for add-sse-line!, remove-sse-line! and
   stop-sync!."
  [pool]
  (let [sync {:lines (atom {})}]
    (assoc sync :listener
           (database/start-listening! pool "groundwork_sync"
                                      {:on-notifications #(tell-of-notifications! sync %)
                                       :on-reconnect     #(tell-every-line! sync {:sync/missed {}})}))))

(defn stop-sync!
  "Stops listening; the lines stay open until the server stops."
  [{:keys [listener]}]
  (database/stop-listening! listener))

(defn count-open-lines
  "How many sse lines are kept, for tests and a status page."
  [{:keys [lines]}]
  (count @lines))
