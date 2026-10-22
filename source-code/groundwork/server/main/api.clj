(ns groundwork.server.main.api
  "The server's one start: the server libraries put together into a
   running app. The browser's start is groundwork.browser.main.

     (def app
       {:settings             settings              ; checked against settings-shape
        :browser-entry        'app.browser.main/start!  ; the function the page runs
        :tables               [shops orders]        ; table maps, with :access and :sync
        :changes              []                    ; cellar's changes
        :scope-kinds          scope-kinds           ; doorman's
        :handlers             handlers              ; mailroom's table, each with :action and shapes
        :pages                {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}}
        :background-job-kinds [send-invoice-email]
        :schedules            []})

     (def running (main/start-app! app))
     (main/stop-app! running)

   start-app! checks the settings (every problem at once), puts Groundwork's
   own tables with the app's, migrates in development (:migrate-at-start?
   true) or else refuses a database that isn't current or a role that could
   pass the gates, then starts the pool, the handlers (doorman's check, then sync),
   sync's listener, the background job workers, in development the browser
   build's watcher (:browser-build :watch), and the server (mailroom's three
   addresses, from :server :addresses, every page answered by the page
   shell, files from :files-folder; no ws hooks).

   In production the deploy runs migrate-app! first, as the role that owns
   the tables (the app's own role owns nothing), and the browser's release
   build (showroom, its own library), whose files the page lists
   (:browser-build :built). Production has neither showroom nor
   shadow-cljs: showroom is loaded only when it watches."
  (:require
   [clojure.string :as str]
   [backroom.api :as backroom]
   [cellar.api :as database]
   [doorman.api :as doorman]
   [groundwork.server.main.page :as page]
   [mailroom.api :as mailroom]
   [groundwork.server.sync.api :as sync]
   [wire.api :as wire]))

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
   [:server mailroom/settings-shape]
   [:database database/settings-shape]
   [:sessions doorman/settings-shape]
   [:background-jobs backroom/settings-shape]
   [:page page-settings-shape]])

(defn check-settings
  "Returns the settings; throws with every problem at once."
  [settings]
  (when-let [errors (wire/find-errors-in-data-for-shape settings-shape settings)]
    (throw (ex-info (str "The settings have problems: " (pr-str errors))
                    {:type :groundwork.server.main/bad-settings :errors errors})))
  settings)

(def groundwork-tables
  "doorman's tables and backroom's, each in their own folder, put with the
   app's. Neither knows of sync: their tables are made silent here."
  (vec (concat (map #(assoc % :sync [:silent "no screen shows sessions"]) [doorman/sessions-table])
               (map #(assoc % :sync [:silent "access is read again on each message; no screen lists it yet"]) doorman/access-tables)
               (map #(assoc % :sync [:silent "no screen shows background jobs"]) backroom/background-jobs-tables))))

(defn make-ready-tables
  "The app's tables and Groundwork's, with their gates (doorman) and change
   triggers (sync): what migrate brings Postgres to."
  [{:keys [tables scope-kinds]}]
  (-> (into (vec tables) groundwork-tables)
      (doorman/put-gates-on-tables scope-kinds)
      (sync/put-sync-on-tables)))

(defn- install-functions-and-migrate! [pool app]
  (doorman/install-access-functions! pool)
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
      (doorman/put-access-into-handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                         :find-session-step find-session-step})
      (sync/put-sync-into-handlers)))

(defn- make-rate-limit-key-finder
  "Askers told apart by their session (a hash of its cookie), else by
   their address, the proxy's header when the settings name one."
  [settings]
  (fn [request]
    (or (doorman/find-session-key-of-request request (:sessions settings))
        (mailroom/find-client-address request (get-in settings [:server :client-address-header])))))

(defn- make-routes [{:keys [settings pages]} handlers running-sync]
  (let [browser-settings (page/make-browser-settings settings pages)]
    (into (mailroom/make-routes-for-messages
           handlers
           (:server settings)
           {:find-rate-limit-key (make-rate-limit-key-finder settings)
            :on-sse-open  (fn [line]
                            (mailroom/send-message-on-sse-line! line {:groundwork/version (:version settings)})
                            (sync/add-sse-line! running-sync line))
            :on-sse-close #(sync/remove-sse-line! running-sync %)
            :on-ws-open   nil
            :on-ws-close  nil})
          (mailroom/make-server-routes-for-pages pages (page/make-page-handler settings browser-settings)))))

(defn- start-watching-browser-build!
  "Loaded here, not required above: production has neither showroom nor
   shadow-cljs."
  [{:keys [browser-entry settings]}]
  ((requiring-resolve 'showroom.api/start-watching!)
   {:entry browser-entry :files-folder (get-in settings [:page :files-folder])}))

(defn- stop-watching-browser-build! [watching]
  ((requiring-resolve 'showroom.api/stop-watching!) watching))

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
  [{:keys [settings background-job-kinds schedules] :as app}]
  (check-settings settings)
  (let [stoppers (atom ())]
    (try
      (let [pool              (start-part! stoppers #(database/start-pool! (:database settings)) database/stop-pool!)
            _                 (if (:migrate-at-start? settings)
                                @(:slow-part (install-functions-and-migrate! pool app))
                                (refuse-unsafe-database! pool app))
            find-session-step (doorman/make-find-session-step pool (:sessions settings))
            handlers          (make-ready-handlers pool app find-session-step)
            running-sync      (start-part! stoppers
                                           #(sync/start-sync! pool)
                                           sync/stop-sync!)
            workers           (start-part! stoppers
                                           #(backroom/start-workers! pool (assoc (:background-jobs settings)
                                                                                 :kinds background-job-kinds
                                                                                 :schedules schedules))
                                           backroom/stop-workers!)
            watching          (when (= :watch (:browser-build settings))
                                (start-part! stoppers #(start-watching-browser-build! app) stop-watching-browser-build!))
            server            (mailroom/start-server!
                               (mailroom/make-ring-handler-from-routes (make-routes app handlers running-sync)
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
  (mailroom/stop-server! server)
  (when watching (stop-watching-browser-build! watching))
  (backroom/stop-workers! workers)
  (sync/stop-sync! sync)
  (database/stop-pool! pool))
