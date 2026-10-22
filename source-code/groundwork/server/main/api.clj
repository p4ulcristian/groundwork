(ns groundwork.server.main.api
  "The server's one start: the server libraries put together into a
   running app. The browser's start is groundwork.browser.main.

     (def app
       {:settings             settings              ; checked against settings-shape
        :browser-entry        'app.browser.main/start!  ; the function the page runs
        :tables               [shops orders]        ; table maps, with :access and :doorbell
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
   pass the gates, then starts the pool, the handlers (doorman's check, then
   doorbell's notes), doorbell's listener, the background job workers, in development the browser
   build's watcher (:browser-build {:watch options}), and the server (mailroom's three
   addresses, from :server :addresses, every page answered by the page
   shell, files from :page :files; no ws hooks).

   In production the deploy runs migrate-app! first, as the role that owns
   the tables (the app's own role owns nothing), and the browser's release
   build (showroom, its own library), whose files the page lists
   (:browser-build :built). Production has neither showroom nor
   shadow-cljs: showroom is loaded only when it watches.

   Two GETs answer whoever runs the app, outside sessions, access and rate
   limits (groundwork.server.main.health, the :health settings): live, 200
   while the process answers; ready, 200 once started while the database
   answers, else 503, and 503 from the start of stop-app!."
  (:require
   [clojure.string :as str]
   [backroom.api :as backroom]
   [cellar.api :as database]
   [doorman.api :as doorman]
   [doorbell.api :as doorbell]
   [groundwork.server.main.health :as health]
   [groundwork.server.main.page :as page]
   [mailroom.api :as mailroom]
   [wire.api :as wire]))

(def page-settings-shape
  "The :page part of the settings, every key required: the page shell's
   details and the files served beside it.
   :title, :language: the shell's <title> and lang.
   :viewport: its viewport meta, \"width=device-width, initial-scale=1\".
   :content-security-policy: the header, or :none for none.
   :referrer-policy: the header, \"same-origin\".
   :frame-options: the X-Frame-Options header, \"DENY\" or \"SAMEORIGIN\",
   or :none when other sites may frame the page.
   :files: mailroom's files: :folder, where the browser build is (the
   watcher writes it, the release build's manifests are read from it, a
   GET no route matches is answered from it); :hashed-name-min-hex-digits,
   how many hex digits make a name hashed (main.3F9A0C1D.js);
   :hashed-file-cache-seconds, how long a browser keeps a hashed file."
  [:map {:closed true}
   [:title :string]
   [:language :string]
   [:viewport :string]
   [:content-security-policy [:or :string [:= :none]]]
   [:referrer-policy :string]
   [:frame-options [:enum "DENY" "SAMEORIGIN" :none]]
   [:files [:map {:closed true}
            [:folder :string]
            [:hashed-name-min-hex-digits [:int {:min 1}]]
            [:hashed-file-cache-seconds [:int {:min 0}]]]]])

(def health-settings-shape
  "The :health part of the settings, every key required: the two GETs for
   whoever runs the app (groundwork.server.main.health).
   :live-path: 200 while the process answers, \"/health/live\".
   :ready-path: 200 when the app has started and the database answers,
   else 503, \"/health/ready\".
   :database-timeout-ms: how long ready waits for the database's answer
   before it answers 503, shorter than the asker's own timeout.
   :not-ready-before-stop-ms: how long stop-app! answers ready with 503,
   still serving, before the server stops taking requests, so a proxy
   that asks ready sends no more; 0 when the proxy stops sending by
   itself (Kamal's)."
  [:map {:closed true}
   [:live-path :string]
   [:ready-path :string]
   [:database-timeout-ms pos-int?]
   [:not-ready-before-stop-ms [:int {:min 0}]]])

(def ^:private part-limits-shape
  [:map {:closed true}
   [:statement-timeout-ms [:or [:= :none] [:int {:min 1}]]]
   [:lock-timeout-ms [:or [:= :none] [:int {:min 1}]]]])

(def migration-settings-shape
  "The :migration part of the settings, every key required: the time limits
   each part of a migration runs under (cellar's migrate! options), in
   place of the pool's. :none is no limit.
   :instant-part: the catalog changes, in one transaction before the app
   starts; a :lock-timeout-ms keeps an ALTER queued behind a long
   transaction from holding every query to its table.
   :slow-part: the index builds and validations over every row, after."
  [:map {:closed true}
   [:instant-part part-limits-shape]
   [:slow-part part-limits-shape]])

(def doorbell-settings-shape
  "The :doorbell part of the settings, every key required, both doorbell's:
   :store, the browser's question store's, which the page carries
   (doorbell's store-settings-shape); :listening, the server's listener's
   (doorbell's listening-settings-shape)."
  [:map {:closed true}
   [:store doorbell/store-settings-shape]
   [:listening doorbell/listening-settings-shape]])

(defn- leaves-entry-and-folder-out? [watch-options]
  (not (or (contains? watch-options :entry) (contains? watch-options :files-folder))))

(def browser-build-settings-shape
  "The :browser-build setting: :built, the release build's files, listed
   by their manifests; or {:watch options}, development, showroom's
   watcher started with these options: every one of showroom's
   start-watching! options but :entry and :files-folder, which come from
   the app's :browser-entry and the :page :files :folder. showroom checks
   them, every key required, before the app starts anything."
  [:or
   [:= :built]
   [:map {:closed true}
    [:watch [:and
             [:map-of :keyword :any]
             [:fn {:error/message ":entry and :files-folder come from :browser-entry and :page :files :folder"}
              leaves-entry-and-folder-out?]]]]])

(def settings-shape
  "The whole settings, every key required; each library brings its part."
  [:map {:closed true}
   [:version :string]
   [:migrate-at-start? :boolean]
   [:browser-build browser-build-settings-shape]
   [:server mailroom/settings-shape]
   [:database database/settings-shape]
   [:migration migration-settings-shape]
   [:sessions doorman/settings-shape]
   [:background-jobs backroom/settings-shape]
   [:doorbell doorbell-settings-shape]
   [:page page-settings-shape]
   [:health health-settings-shape]])

(defn check-settings
  "Returns the settings; throws with every problem at once."
  [settings]
  (when-let [errors (wire/find-errors-in-data-for-shape settings-shape settings)]
    (throw (ex-info (str "The settings have problems: " (pr-str errors))
                    {:type :groundwork.server.main/bad-settings :errors errors})))
  settings)

(def groundwork-tables
  "doorman's tables and backroom's, each in their own folder, put with the
   app's. Neither knows of doorbell: their tables are made silent here."
  (vec (concat (map #(assoc % :doorbell [:silent "no screen shows sessions"]) [doorman/sessions-table])
               (map #(assoc % :doorbell [:silent "access is read again on each message; no screen lists it yet"]) doorman/access-tables)
               (map #(assoc % :doorbell [:silent "no screen shows background jobs"]) backroom/background-jobs-tables))))

(defn make-ready-tables
  "The app's tables and Groundwork's, with their gates (doorman) and change
   triggers (doorbell): what migrate brings Postgres to."
  [{:keys [tables scope-kinds]}]
  (-> (into (vec tables) groundwork-tables)
      (doorman/put-gates-on-tables scope-kinds)
      (doorbell/put-doorbell-on-tables)))

(defn- install-functions-and-migrate! [pool app]
  (doorman/install-access-functions! pool)
  (doorbell/install-tell-function! pool)
  (database/migrate! pool (make-ready-tables app) (:changes app) (get-in app [:settings :migration])))

(defn migrate-app!
  "For the deploy, before the app starts: as the role that owns the tables
   (owner-db-settings), installs Groundwork's functions, brings Postgres to
   the tables and changes (waiting for the slow part too), each part under
   the :migration settings' time limits, and lets the app's own role
   (app-role) use them. Prints the plan."
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
      (doorbell/put-table-notes-into-handlers)))

(defn- make-rate-limit-key-finder
  "Askers told apart by their session (a hash of its cookie), else by
   their address, the proxy's header when the settings name one."
  [settings]
  (fn [request]
    (or (doorman/find-session-key-of-request request (:sessions settings))
        (mailroom/find-client-address request (get-in settings [:server :client-address-header])))))

(defn- make-routes [{:keys [settings pages]} pool running-health handlers running-doorbell]
  (let [browser-settings (page/make-browser-settings settings pages)]
    (-> (health/make-health-routes pool running-health settings)
        (into (mailroom/make-routes-for-messages
               handlers
               (:server settings)
               {:find-rate-limit-key (make-rate-limit-key-finder settings)
                :on-sse-open  (fn [line]
                                (mailroom/send-message-on-sse-line! line {:groundwork/version (:version settings)} nil)
                                (doorbell/add-sse-line! running-doorbell line))
                :on-sse-close #(doorbell/remove-sse-line! running-doorbell %)
                :on-ws-open   nil
                :on-ws-close  nil}))
        (into (mailroom/make-server-routes-for-pages pages (page/make-page-handler settings browser-settings))))))

(defn- is-watching? [settings]
  (map? (:browser-build settings)))

(defn- make-watch-options
  "showroom's start-watching! options: the :browser-build :watch settings,
   with the app's entry and the files folder."
  [{:keys [browser-entry settings]}]
  (assoc (get-in settings [:browser-build :watch])
         :entry browser-entry
         :files-folder (get-in settings [:page :files :folder])))

(defn- check-watch-options!
  "showroom checks the watch options, every key required, before anything
   starts: showroom's make-build checks them and builds nothing."
  [app]
  ((requiring-resolve 'showroom.api/make-build) :watch (make-watch-options app)))

(defn- start-watching-browser-build!
  "Loaded here, not required above: production has neither showroom nor
   shadow-cljs."
  [app]
  ((requiring-resolve 'showroom.api/start-watching!) (make-watch-options app)))

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
   {:settings :pool :handlers :doorbell :workers :watching :health :server}, :server holding the
   :port it listens on."
  [{:keys [settings background-job-kinds schedules] :as app}]
  (check-settings settings)
  (when (is-watching? settings) (check-watch-options! app))
  (let [stoppers (atom ())]
    (try
      (let [pool              (start-part! stoppers #(database/start-pool! (:database settings)) database/stop-pool!)
            _                 (if (:migrate-at-start? settings)
                                @(:slow-part (install-functions-and-migrate! pool app))
                                (refuse-unsafe-database! pool app))
            find-session-step (doorman/make-find-session-step pool (:sessions settings))
            handlers          (make-ready-handlers pool app find-session-step)
            running-doorbell  (start-part! stoppers
                                           #(doorbell/start-listening! pool (get-in settings [:doorbell :listening]))
                                           doorbell/stop-listening!)
            workers           (start-part! stoppers
                                           #(backroom/start-workers! pool (assoc (:background-jobs settings)
                                                                                 :kinds background-job-kinds
                                                                                 :schedules schedules))
                                           backroom/stop-workers!)
            watching          (when (is-watching? settings)
                                (start-part! stoppers #(start-watching-browser-build! app) stop-watching-browser-build!))
            running-health    (health/make-health)
            server            (mailroom/start-server!
                               (mailroom/make-ring-handler-from-routes (make-routes app pool running-health handlers running-doorbell)
                                                                        {:files (get-in settings [:page :files])})
                               (:server settings))]
        (health/mark-stage! running-health :started)
        {:settings settings :pool pool :handlers handlers :doorbell running-doorbell :workers workers
         :watching watching :health running-health :server server})
      (catch Throwable e
        (doseq [stop @stoppers] (stop))
        (throw e)))))

(defn stop-app!
  "Answers ready with 503 for the :health settings' :not-ready-before-stop-ms,
   then stops taking requests, stops the watcher, lets running background
   jobs finish, stops listening, closes the pool."
  [{:keys [settings pool server workers watching] running-doorbell :doorbell running-health :health}]
  (health/mark-stage! running-health :stopping)
  (Thread/sleep (long (get-in settings [:health :not-ready-before-stop-ms])))
  (mailroom/stop-server! server)
  (when watching (stop-watching-browser-build! watching))
  (backroom/stop-workers! workers)
  (doorbell/stop-listening! running-doorbell)
  (database/stop-pool! pool))
