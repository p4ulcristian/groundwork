(ns groundwork.server.main.api
  "The server's one start: the server libraries put together into a
   running app. The browser's start is groundwork.browser.main.

     (def app
       {:settings             settings              ; checked against settings-shape
        :browser-entry        'app.browser.main/start!  ; the function the page runs
        :tables               [shops orders]        ; table maps, with :access and :sync
        :changes              []                    ; groundwork.server.database's changes
        :scope-kinds          scope-kinds           ; groundwork.server.access
        :handlers             handlers              ; transport's table, each with :action and shapes
        :pages                {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}}
        :background-job-kinds [send-invoice-email]
        :schedules            []})

     (def running (main/start-app! app))
     (main/stop-app! running)

   start-app! checks the settings (every problem at once), puts Groundwork's
   own tables with the app's, migrates in development (:migrate-at-start?
   true) or else refuses a database that isn't current or a role that could
   pass the gates, then starts the pool, the handlers (access, then sync),
   sync's listener, the background job workers, in development the browser
   build's watcher (:browser-build :watch), and the server (transport's three
   addresses, every page answered by the page shell, files from
   :files-folder).

   In production the deploy runs migrate-app! first, as the role that owns
   the tables (the app's own role owns nothing), and the browser's release
   build (groundwork.server.browser-build), whose files the page lists
   (:browser-build :built). Production has no shadow-cljs: the watcher's
   library is loaded only when it watches."
  (:require
   [clojure.string :as str]
   [groundwork.server.access.api :as access]
   [groundwork.server.background-jobs.api :as background-jobs]
   [groundwork.server.database.api :as database]
   [groundwork.server.main.page :as page]
   [groundwork.server.sessions.api :as sessions]
   [groundwork.server.steps.api]
   [groundwork.server.sync.api :as sync]
   [groundwork.server.transport.api :as transport]
   [groundwork.server.wire.api :as wire]))

(def page-settings-shape
  "The :page part of the settings: the page shell's details."
  [:map {:closed true}
   [:title :string]
   [:language :string]
   [:content-security-policy [:or :string [:= :none]]]
   [:files-folder :string]])

(def settings-shape
  "The whole settings, every key required; each library brings its part."
  [:map {:closed true}
   [:version :string]
   [:migrate-at-start? :boolean]
   [:browser-build [:enum :watch :built]]
   [:server transport/settings-shape]
   [:database database/settings-shape]
   [:sessions sessions/settings-shape]
   [:background-jobs background-jobs/settings-shape]
   [:page page-settings-shape]])

(defn check-settings
  "Returns the settings; throws with every problem at once."
  [settings]
  (when-let [errors (wire/find-errors-in-data-for-shape settings-shape settings)]
    (throw (ex-info (str "The settings have problems: " (pr-str errors))
                    {:type :groundwork.server.main/bad-settings :errors errors})))
  settings)

(def groundwork-tables
  "Groundwork's own tables, in its own folder, put with the app's."
  (vec (concat [sessions/sessions-table] access/access-tables background-jobs/background-jobs-tables)))

(defn make-ready-tables
  "The app's tables and Groundwork's, with their gates (access) and change
   triggers (sync): what migrate brings Postgres to."
  [{:keys [tables scope-kinds]}]
  (-> (into (vec tables) groundwork-tables)
      (access/put-gates-on-tables scope-kinds)
      (sync/put-sync-on-tables scope-kinds)))

(defn- install-functions-and-migrate! [pool app]
  (access/install-access-functions! pool)
  (sync/install-sync-function! pool)
  (database/migrate! pool (make-ready-tables app) (:changes app)))

(defn migrate-app!
  "For the deploy, before the app starts: as the role that owns the tables
   (owner-db-settings), installs Groundwork's functions, brings Postgres to
   the tables and changes (waiting for the slow part too), and lets the
   app's own role (app-role) use them. Prints the plan."
  [app owner-db-settings app-role]
  (let [pool (database/start-pool! owner-db-settings)]
    (try
      (deref (:slow-part (install-functions-and-migrate! pool app)))
      (database/grant-tables-to-role! pool (make-ready-tables app) app-role)
      (finally (database/stop-pool! pool)))))

(def ^:private find-powers-of-role
  (database/make-query-from-sql-file "groundwork/server/main/find_powers_of_role.sql"))

(defn- find-folders [tables]
  (set (map #(or (some-> (namespace (:table %)) (str/replace "-" "_")) "public") tables)))

(defn- refuse-unsafe-database! [pool app]
  (let [tables (make-ready-tables app)
        {:keys [passes-gates owns-tables]} (database/find-one-row pool find-powers-of-role
                                                                  {:folders (str/join "," (find-folders tables))})]
    (when (or passes-gates owns-tables)
      (throw (ex-info "The app's database role owns tables or passes the gates; it must own nothing (run migrate-app! as the owner)"
                      {:type :groundwork.server.main/role-too-strong})))
    (when-not (database/does-database-match-tables? pool tables (:changes app))
      (throw (ex-info "The database isn't current: run migrate-app! first"
                      {:type :groundwork.server.main/not-migrated})))))

(defn- make-ready-handlers [pool {:keys [handlers tables scope-kinds]} find-session-step]
  (-> handlers
      (access/put-access-into-handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                        :find-session-step find-session-step})
      (sync/put-sync-into-handlers)))

(defn- make-routes [{:keys [settings pages]} handlers running-sync]
  (let [browser-settings (page/make-browser-settings settings transport/default-addresses pages)]
    (into (transport/make-routes-for-messages
           handlers
           {:on-sse-open  (fn [line]
                            (transport/send-message-on-sse-line! line {:groundwork/version (:version settings)})
                            (sync/add-sse-line! running-sync line))
            :on-sse-close #(sync/remove-sse-line! running-sync %)})
          (transport/make-server-routes-for-pages pages (page/make-page-handler settings browser-settings)))))

(defn- start-watching-browser-build!
  "Loaded here, not required above: production has no shadow-cljs."
  [{:keys [browser-entry settings]}]
  ((requiring-resolve 'groundwork.server.browser-build.api/start-watching!)
   {:entry browser-entry :files-folder (get-in settings [:page :files-folder])}))

(defn- stop-watching-browser-build! [watching]
  ((requiring-resolve 'groundwork.server.browser-build.api/stop-watching!) watching))

(defn- start-part!
  "Starts one part, remembering how to stop it, so a failed start stops
   what had started, last first."
  [stoppers start stop]
  (let [part (start)]
    (swap! stoppers conj #(stop part))
    part))

(defn start-app!
  "Starts the app (see the namespace). Returns the running app, for stop-app!:
   {:settings :pool :handlers :sync :workers :server}, :server holding the
   :port it listens on."
  [{:keys [settings scope-kinds background-job-kinds schedules] :as app}]
  (check-settings settings)
  (let [stoppers (atom ())]
    (try
      (let [pool              (start-part! stoppers #(database/start-pool! (:database settings)) database/stop-pool!)
            _                 (if (:migrate-at-start? settings)
                                @(:slow-part (install-functions-and-migrate! pool app))
                                (refuse-unsafe-database! pool app))
            find-session-step (sessions/make-find-session-step pool (:sessions settings))
            handlers          (make-ready-handlers pool app find-session-step)
            running-sync      (start-part! stoppers
                                           #(sync/start-sync! pool {:tables            (make-ready-tables app)
                                                                    :scope-kinds       scope-kinds
                                                                    :find-session-step find-session-step})
                                           sync/stop-sync!)
            workers           (start-part! stoppers
                                           #(background-jobs/start-workers! pool (assoc (:background-jobs settings)
                                                                                        :kinds background-job-kinds
                                                                                        :schedules schedules))
                                           background-jobs/stop-workers!)
            watching          (when (= :watch (:browser-build settings))
                                (start-part! stoppers #(start-watching-browser-build! app) stop-watching-browser-build!))
            server            (transport/start-server!
                               (transport/make-ring-handler-from-routes (make-routes app handlers running-sync)
                                                                        {:files-folder (get-in settings [:page :files-folder])})
                               (:server settings))]
        {:settings settings :pool pool :handlers handlers :sync running-sync :workers workers
         :watching watching :server server})
      (catch Throwable e
        (doseq [stop @stoppers] (stop))
        (throw e)))))

(defn stop-app!
  "Stops taking requests, stops the watcher, lets running background jobs
   finish, stops listening, closes the pool."
  [{:keys [pool sync server workers watching]}]
  (transport/stop-server! server)
  (when watching (stop-watching-browser-build! watching))
  (background-jobs/stop-workers! workers)
  (sync/stop-sync! sync)
  (database/stop-pool! pool))
