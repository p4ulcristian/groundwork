(ns groundwork.server.main.api
  "The server's one start: the server libraries put together into a
   running app. The browser's start is groundwork.browser.main.

     (def app
       {:settings             (safe/load {:file  \"settings/production.edn\"   ; safe's: #secret and #env filled,
                                          :shape main/settings-shape          ; checked against settings-shape
                                          :env   (System/getenv)})
        :browser-entry        'app.browser.main/start!  ; the function the page runs
        :browser-modules      {}                    ; showroom's :modules, loaded on demand: {:admin {:entries [app.admin.page] :depends-on #{:main}}}
        :tables               [shops orders]        ; table maps, with :access and :doorbell
        :changes              []                    ; cellar's changes
        :scope-kinds          scope-kinds           ; doorman's
        :handlers             handlers              ; bridge's table, each with :action and shapes
        :pages                {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}
                               :admin/page  {:address \"/admin\" :title \"Admin\" :module \"admin\"}}   ; loaded on the way in
        :background-job-kinds [send-invoice-email]
        :schedules            []
        :may-upload?          (fn [{:keys [person-id]}] (some? person-id))   ; who may upload files, the asker
        :on-delivery-event    {:orders/confirmation mark-address-bounced!} ; postbox's, by the email's tag
        :on-inbound-email     {\"quotes\" add-quote-request!}})           ; postbox's, by the mailbox

     (def running (main/start-app! app))
     (main/stop-app! running)

   The settings are one EDN file per environment, loaded by safe: every
   secret in them (the database's password, the :errors and :metrics
   tokens, the bucket's keys, Postmark's and the webhooks' tokens) is
   safe's, which prints as its name, revealed only where it is used; text
   written out where a secret belongs is refused.

   start-app! checks the settings (every problem at once), makes the app's
   one wire from the :wire settings (wire.api's make-wire-from-settings)
   and passes it to every library that sends or reads data across the
   app's edges (bridge's routes, backroom's workers, the page, the
   health checks; no library makes its own), makes the app's one logbook
   from the :logbook settings and passes its log to every library that
   writes a line (cellar's migrate, doorbell's listener, backroom's
   workers, showroom's watcher, bridge's routes and server, the health
   checks; each message's steps get one with its request id, the person
   added once doorman finds them), puts groundwork's own tables with
   the app's, migrates in development (:migrate-at-start?
   true) or else refuses a database that isn't current or a role that could
   pass the gates, then starts the pool, the handlers (doorman's check,
   groundwork's :doorman/my-actions, then doorbell's notes; every write keyed
   to run once, groundwork.server.main.run-once), doorbell's listener, the
   background job workers (groundwork's own that forgets old one-time keys
   among them), in development the browser
   build's watcher (:browser-build {:watch options}, with the app's
   :browser-modules), and the server (bridge's three addresses, from
   :server :addresses, every page answered by the page shell in the
   request's language, files from :page :files; no ws hooks). Every
   request first gets its language (dictionary's, from the :languages
   settings) and loses its address's language prefix, for routing. Each
   language's catalog is read at the start (a missing one refuses it) and
   served where the browser fetches it, /<catalogs-path>/<language>.json. A page
   may name a :module of :browser-modules, loaded on demand by the browser.

   In production the deploy runs migrate-app! first, as the role that owns
   the tables (the app's own role owns nothing), and the browser's release
   build (showroom, its own library), whose files the page lists
   (:browser-build :built). Production has neither showroom nor
   shadow-cljs: showroom is loaded only when it watches.

   Two GETs answer whoever runs the app, outside sessions, access and rate
   limits (groundwork.server.main.health, the :health settings): live, 200
   while the process answers; ready, 200 once started while the database
   answers, else 503, and 503 from the start of stop-app!.

   One more GET, for a monitor, outside them too (groundwork.server.main.errors,
   the :errors settings): the :error lines logbook keeps, after ?since=N,
   only with the settings' bearer token. And one handler of groundwork's own,
   :groundwork/browser-error: the browser's uncaught errors, sent by
   groundwork.browser.main, written at :error as :browser/uncaught, so they are
   kept with the server's; its rate limit is the :errors settings'.
   And one of doorman's, :doorman/my-actions (make-my-actions-handler):
   the asker's actions by scope, and the open ones under nil kind and id,
   which the browser's start names as parlor's :actions-question; its rate
   limit is the :my-actions settings'.

   One meter (meter's make-meter, from the :meter settings) is made once
   and handed to every library that counts (cellar's pool, bridge's
   routes, doorbell's listener, backroom's workers), with groundwork's own
   (the stage, the ready checks, the version) and logbook's counts, read
   when written (groundwork.server.main.metrics). Its text is one more GET, at
   the :metrics settings' :path, beside health and errors, outside them
   too, only with its bearer token (meter's make-metrics-routes).

   A visitor's session (doorman's start-visitor-session!) is a POST at the
   :visitors settings' :path, from the app's own pages only, rate-limited
   by the asker's address (:visitors :rate-limit, 429 over it;
   groundwork.server.main.visitors).

   The REPL (study's start-repl-server!, the :study settings) starts last
   and stops first, with groundwork's tools in study.tools:
   :ask-as-person, :why-refused and :explain-setting
   (groundwork.server.main.tools).

   A table map of the app's says :access and :doorbell, never :gates or
   :on-change, which doorman and doorbell put on it: one that writes
   either is refused at the start, named.

   The app's files (attic's make-attic, the :attic :storage settings)
   and its email (postbox's make-postbox, the :postbox :sending settings)
   are made once, on the app's log and meter (and wire, postbox's), and
   held by the running app (:attic, :postbox); their tables (attic.files,
   postbox.emails) are migrated with groundwork's, postbox's background job
   kind runs with the app's. Files are uploaded at the :attic :upload
   :path (groundwork.server.main.files: doorman's session step, then the app's
   :may-upload?, (fn [asker])); the app deletes a file with attic's
   delete-file! on (:attic running) and (:pool running), after its own
   rows are kept (only a file no row names through a reference to
   :file/key goes). The provider's webhooks are postbox's, at the :postbox
   :webhooks paths, handing events to the app's :on-delivery-event (by
   tag) and :on-inbound-email (by mailbox), each run as doorman's system
   actor :postbox, which the app's sentences give what it may do:
   {:allow :shop-emails/mark-bounced :to [:system :postbox]}."
  (:require
   [clojure.string :as str]
   [attic.api :as attic]
   [backroom.api :as backroom]
   [cellar.api :as database]
   [dictionary.api :as dictionary]
   [doorman.api :as doorman]
   [doorbell.api :as doorbell]
   [groundwork.server.main.errors :as errors]
   [groundwork.server.main.files :as files]
   [groundwork.server.main.health :as health]
   [groundwork.server.main.metrics :as metrics]
   [groundwork.server.main.page :as page]
   [groundwork.server.main.run-once :as run-once]
   [groundwork.server.main.tools :as tools]
   [groundwork.server.main.visitors :as visitors]
   [logbook.api :as logbook]
   [bridge.api :as bridge]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [meter.api :as meter]
   [postbox.api :as postbox]
   [safe.api :as safe]
   [study.api :as study]
   [wire.api :as wire]))

(def page-settings-shape
  "The :page part of the settings, every key required: the page shell's
   details and the files served beside it. Its lang is the request's
   language (the :languages settings).
   :title: the shell's <title>.
   :viewport: its viewport meta, \"width=device-width, initial-scale=1\".
   :content-security-policy: the header, or :none for none.
   :referrer-policy: the header, \"same-origin\".
   :frame-options: the X-Frame-Options header, \"DENY\" or \"SAMEORIGIN\",
   or :none when other sites may frame the page.
   :files: bridge's files: :folder, where the browser build is (the
   watcher writes it, the release build's manifests are read from it, a
   GET no route matches is answered from it); :hashed-name-min-hex-digits,
   how many hex digits make a name hashed (main.3F9A0C1D.js);
   :hashed-file-cache-seconds, how long a browser keeps a hashed file."
  [:map {:closed true}
   [:title :string]
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

(defn- leaves-entry-folder-and-log-out? [watch-options]
  (not-any? #(contains? watch-options %) [:entry :modules :files-folder :log]))

(def browser-build-settings-shape
  "The :browser-build setting: :built, the release build's files, listed
   by their manifests; or {:watch options}, development, showroom's
   watcher started with these options: every one of showroom's
   start-watching! options but :entry, :modules, :files-folder and :log,
   which come from the app's :browser-entry and :browser-modules, the
   :page :files :folder and the app's log. showroom checks them, every key required, before the app starts
   anything."
  [:or
   [:= :built]
   [:map {:closed true}
    [:watch [:and
             [:map-of :keyword :any]
             [:fn {:error/message ":entry, :modules, :files-folder and :log come from :browser-entry, :browser-modules, :page :files :folder and the app's log"}
              leaves-entry-folder-and-log-out?]]]]])

(def browser-wire-settings-shape
  "The :browser-wire part of the settings, every key required: the
   browser's wire settings (wire's settings-shape in the browser) but
   :exact-decimals, a function, which the app's browser start hands to
   groundwork.browser.main's start-app!. The page carries them.
   :most-shapes-kept: how many shapes' tools the browser's wire keeps
   before all are forgotten and made again as used.
   :most-keys-kept: the same for the JSON keys' keywords."
  [:map {:closed true}
   [:most-shapes-kept [:int {:min 1}]]
   [:most-keys-kept [:int {:min 1}]]])

(def errors-settings-shape
  "The :errors part of the settings, every key required: the errors a
   monitor pulls, and the browser's (groundwork.server.main.errors).
   :path: the GET a monitor asks the kept errors from, \"/groundwork/errors\".
   :token: the bearer token it must send (Authorization: Bearer …), a
   secret of safe's (#secret :errors/token in the app's settings file);
   text written out is refused.
   :browser-rate-limit: how many browser errors one asker may send,
   {:max 10 :per-ms 60000}; more are answered \"Too many requests.\" and
   not written."
  [:map {:closed true}
   [:path :string]
   [:token safe/secret]
   [:browser-rate-limit [:map {:closed true}
                         [:max [:int {:min 1}]]
                         [:per-ms [:int {:min 1}]]]]])

(def my-actions-settings-shape
  "The :my-actions part of the settings, every key required: groundwork's
   :doorman/my-actions handler (doorman's make-my-actions-handler), which
   the browser asks for parlor's \"can I?\".
   :rate-limit: how many times one asker may ask, {:max 60 :per-ms
   60000}, or nil for no limit; more are answered \"Too many requests.\"."
  [:map {:closed true}
   [:rate-limit [:maybe [:map {:closed true}
                         [:max [:int {:min 1}]]
                         [:per-ms [:int {:min 1}]]]]]])

(def visitors-settings-shape
  "The :visitors part of the settings, every key required: the route that
   gives a visitor (someone not signed in) a session of their own,
   doorman's start-visitor-session! (groundwork.server.main.visitors).
   :path: the POST the browser sends the first time the app keeps
   something for a visitor, \"/groundwork/visitor\"; the page carries it to
   groundwork.browser.main's start-visitor-session!.
   :rate-limit: how many times one asker, told apart by their address
   (the :server settings' :client-address-header behind a proxy), may POST
   it, {:max 10 :per-ms 60000}; more are answered 429 and make no session."
  [:map {:closed true}
   [:path [:re #"^/"]]
   [:rate-limit [:map {:closed true}
                 [:max [:int {:min 1}]]
                 [:per-ms [:int {:min 1}]]]]])

(def attic-settings-shape
  "The :attic part of the settings, every key required, both attic's:
   :storage, the bucket's (attic's settings-shape: :endpoint :bucket
   :access-key :secret-key :region :public-url :link-expiry-seconds
   :connect-timeout-ms :request-timeout-ms :retries), which groundwork makes
   the app's one attic from; :upload, the upload route's: :path, the POST
   the browser sends files to, \"/groundwork/files\", and attic's upload
   settings (attic's upload-settings-shape: :key-prefix
   :allowed-content-types :max-file-bytes :max-files). :max-file-bytes
   must be below the :server settings' :max-body-bytes, which holds the
   whole upload."
  [:map {:closed true}
   [:storage attic/settings-shape]
   [:upload (into [:map {:closed true} [:path [:re #"^/"]]] (drop 2 attic/upload-settings-shape))]])

(def postbox-settings-shape
  "The :postbox part of the settings, every key required, both postbox's:
   :sending, the postbox's (postbox's settings-shape: :provider, :postmark
   or :log, :tags, :background-job), which groundwork makes the app's one
   postbox from; :webhooks, the provider's two routes (postbox's
   webhook-settings-shape: :delivery-path :inbound-path :token
   :inbound-domain)."
  [:map {:closed true}
   [:sending postbox/settings-shape]
   [:webhooks postbox/webhook-settings-shape]])

(defn- do-retries-end-before-keys-are-forgotten?
  "Asked only of numbers: a missing one is the map's problem, named there."
  [{:keys [keep-keys-ms] {:keys [times wait-ms]} :retry-writes}]
  (or (not (every? int? [keep-keys-ms times wait-ms]))
      (< (* times wait-ms) keep-keys-ms)))

(def run-once-settings-shape
  "The :run-once part of the settings, every key required: every write
   runs once, by its one-time key (groundwork.server.main.run-once).
   :keep-keys-ms: how long a key is kept; a repeat after it runs again
   (a day, 86400000).
   :forget-keys: groundwork's background job that deletes the keys kept longer:
   :cron, when it runs (UTC, backroom's cron line, \"*/15 * * * *\");
   :timeout-ms and :attempts, backroom's for its kind.
   :retry-writes: the browser's, which the page carries to parlor: a write
   that got no answer is sent again with the same key, up to :times more,
   :wait-ms apart; the retries must end before the key is forgotten."
  [:and
   [:map {:closed true}
    [:keep-keys-ms [:int {:min 1}]]
    [:forget-keys [:map {:closed true}
                   [:cron :string]
                   [:timeout-ms [:int {:min 1}]]
                   [:attempts [:int {:min 1}]]]]
    [:retry-writes [:map {:closed true}
                    [:times [:int {:min 0}]]
                    [:wait-ms [:int {:min 0}]]]]]
   [:fn {:error/message "the retries end before the key is forgotten: :retry-writes :times × :wait-ms below :keep-keys-ms"}
    do-retries-end-before-keys-are-forgotten?]])

(def settings-shape
  "The whole settings, every key required; each library brings its part.
   :wire is wire's settings on the JVM (wire.api/settings-shape), which
   groundwork makes the app's one wire from; :browser-wire the browser's.
   :logbook is logbook's settings (logbook.api/settings-shape), which
   groundwork makes the app's one logbook from; :errors where its kept errors
   are pulled, and how often a browser may send one; :my-actions how often
   a browser may ask what its person may do; :run-once how long a write's
   one-time key is kept, and how often a browser retries a write;
   :languages the app's languages (dictionary's settings), which a
   request's, and its page's, is picked from; :meter the app's one
   meter's (meter.api/settings-shape: :jvm-metrics, and
   :most-series-per-metric, which must cover the largest declaration:
   bridge's messages_total, (handlers+1)×24, its answer_seconds,
   (handlers+1)×(buckets+3), cellar's query_seconds, (queries+1)×(buckets+3),
   the handlers counting groundwork's two and the queries every library's);
   :metrics where Prometheus pulls its text (meter's routes settings,
   :path and :token); :study the REPL's (study.api/settings-shape:
   :development, :ssh-only or :off with the emergency switch); :visitors
   where a visitor's session is started; :attic the app's files, the
   bucket and the upload route; :postbox its email, the provider and the
   webhooks."
  [:map {:closed true}
   [:version :string]
   [:wire wire/settings-shape]
   [:logbook logbook/settings-shape]
   [:meter meter/settings-shape]
   [:metrics meter/routes-settings-shape]
   [:study study/settings-shape]
   [:errors errors-settings-shape]
   [:browser-wire browser-wire-settings-shape]
   [:migrate-at-start? :boolean]
   [:browser-build browser-build-settings-shape]
   [:server bridge/settings-shape]
   [:database database/settings-shape]
   [:migration migration-settings-shape]
   [:sessions doorman/settings-shape]
   [:visitors visitors-settings-shape]
   [:my-actions my-actions-settings-shape]
   [:run-once run-once-settings-shape]
   [:languages dictionary/settings-shape]
   [:background-jobs backroom/settings-shape]
   [:attic attic-settings-shape]
   [:postbox postbox-settings-shape]
   [:doorbell doorbell-settings-shape]
   [:page page-settings-shape]
   [:health health-settings-shape]])

(defn- throw-bad-settings [errors]
  (throw (ex-info (str "The settings have problems: " (pr-str errors))
                  {:type :groundwork.server.main/bad-settings :errors errors})))

(defn check-settings
  "Returns the settings; throws with every problem at once. Checked by
   malli itself: the settings are values the app hands in, and the wire is
   made from them only once they fit. Then across parts: one uploaded
   file fits in the server's body limit."
  [settings]
  (when-let [explanation (malli/explain settings-shape settings)]
    (throw-bad-settings (malli-error/humanize explanation)))
  (when (>= (get-in settings [:attic :upload :max-file-bytes]) (get-in settings [:server :max-body-bytes]))
    (throw-bad-settings {:attic {:upload {:max-file-bytes ["should be below the :server settings' :max-body-bytes, which holds the whole upload"]}}}))
  settings)

(def groundwork-tables
  "doorman's tables, backroom's, attic's and postbox's, each in their own
   folder, and groundwork's own one-time keys (groundwork.run_once_keys), put with
   the app's. None knows of doorbell; their doorbells are said here: the
   sessions, the background jobs, the files, the emails and the keys
   silent; doorman's access tables tell, so a screen's
   :doorman/my-actions is asked again when a membership or a role
   changes."
  (vec (concat [(assoc run-once/run-once-keys-table :doorbell [:silent "no screen shows one-time keys"])]
               (map #(assoc % :doorbell [:silent "no screen shows sessions"]) [doorman/sessions-table])
               (map #(assoc % :doorbell :tell) doorman/access-tables)
               (map #(assoc % :doorbell [:silent "no screen shows background jobs"]) backroom/background-jobs-tables)
               [(assoc attic/files-table :doorbell [:silent "a screen shows the app's rows that name a file, not attic's"])
                (assoc postbox/emails-table :doorbell [:silent "no screen shows postbox's emails"])])))

(defn- check-tables-leave-gates-and-on-change-out
  "The app's table maps say :access and :doorbell, never :gates or
   :on-change: those are what doorman and doorbell put on them from
   :access and :doorbell, and a table's own would be overwritten without
   a word. Throws naming every table that writes either, as doorman
   refuses a handler's own :run-steps-in."
  [tables]
  (let [problems (for [{:keys [table] :as table-map} tables
                       taken [:gates :on-change]
                       :when (contains? table-map taken)]
                   (str table " writes its own " taken ", which groundwork puts on it: "
                        (if (= :gates taken) "doorman's, from its :access" "doorbell's, from its :doorbell")))]
    (when (seq problems)
      (throw (ex-info (str "The tables have problems: " (pr-str (vec problems)))
                      {:type :groundwork.server.main/bad-tables :problems (vec problems)}))))
  tables)

(defn- list-all-tables
  "The app's tables, each checked to leave :gates and :on-change out, and
   groundwork's."
  [{:keys [tables]}]
  (into (vec (check-tables-leave-gates-and-on-change-out tables)) groundwork-tables))

(defn make-ready-tables
  "The app's tables and groundwork's, with their gates (doorman) and change
   triggers (doorbell): what migrate brings Postgres to. An app's table
   that writes its own :gates or :on-change is refused, named."
  [{:keys [scope-kinds] :as app}]
  (-> (list-all-tables app)
      (doorman/put-gates-on-tables scope-kinds)
      (doorbell/put-doorbell-on-tables)))

(defn- install-functions-and-migrate! [pool {:keys [scope-kinds] :as app} log]
  (doorman/install-access-functions! pool (list-all-tables app) scope-kinds)
  (doorbell/install-tell-function! pool)
  (database/migrate! pool (make-ready-tables app) (:changes app) (assoc (get-in app [:settings :migration]) :log log)))

(defn migrate-app!
  "For the deploy, before the app starts: as the role that owns the tables
   (owner-db-settings), installs doorman's and doorbell's functions, brings Postgres to
   the tables and changes (waiting for the slow part too), each part under
   the :migration settings' time limits, and lets the app's own role
   (app-role) use them. Each planned statement is written to a log made
   from the :logbook settings (:cellar/planned). The owner's pool counts
   on a meter of its own, never served (:jvm-metrics false): cellar takes
   one meter per pool, and the deploy's run has no /metrics."
  [app owner-db-settings app-role]
  (let [settings (:settings app)
        log      (logbook/log-function (logbook/make-logbook (:logbook settings)))
        meter    (meter/make-meter {:jvm-metrics            false
                                    :most-series-per-metric (get-in settings [:meter :most-series-per-metric])}
                                   {:log log})
        pool     (database/start-pool! owner-db-settings {:log log :meter meter})]
    (try
      (deref (:slow-part (install-functions-and-migrate! pool app log)))
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

(defn- add-my-actions-handler
  "The handlers with groundwork's :doorman/my-actions, doorman's
   make-my-actions-handler: the asker's actions for the browser (parlor's
   \"can I?\"). An app's own handler of that name is refused."
  [handlers pool {:keys [tables scope-kinds settings]} find-session-step]
  (when (contains? handlers :doorman/my-actions)
    (throw (ex-info "The handler name :doorman/my-actions is groundwork's own; the app's handlers use it"
                    {:type :groundwork.server.main/handler-name-taken :handler :doorman/my-actions})))
  (assoc handlers :doorman/my-actions
         (doorman/make-my-actions-handler {:pool              pool
                                           :tables            tables
                                           :scope-kinds       scope-kinds
                                           :find-session-step find-session-step
                                           :rate-limit        (get-in settings [:my-actions :rate-limit])})))

(defn- make-ready-handlers [pool {:keys [handlers tables scope-kinds settings] :as app} find-session-step]
  (-> handlers
      (run-once/put-run-once-into-handlers scope-kinds (get-in settings [:run-once :keep-keys-ms]))
      (doorman/put-access-into-handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                         :find-session-step find-session-step})
      (add-my-actions-handler pool app find-session-step)
      (doorbell/put-table-notes-into-handlers)))

(defn- make-rate-limit-key-finder
  "Askers told apart by their session (a hash of its cookie), else by
   their address, the proxy's header when the settings name one."
  [settings]
  (fn [request]
    (or (doorman/find-session-key-of-request request (:sessions settings))
        (bridge/find-client-address request (get-in settings [:server :client-address-header])))))

(def ^:private postbox-system-actor
  "The system actor postbox's webhook functions run as (doorman's
   run-as-system!): the app's sentences give it what they may do,
   {:allow :shop-emails/mark-bounced :to [:system :postbox]}."
  :postbox)

(defn- make-routes [{:keys [settings pages browser-modules may-upload? on-delivery-event on-inbound-email]}
                    {:keys [wire running-logbook log app-meter pool running-health handlers running-doorbell
                            running-attic running-postbox find-session-step]}]
  (let [browser-settings (page/make-browser-settings settings pages browser-modules)]
    (-> (health/make-health-routes wire log app-meter pool running-health settings)
        (into (errors/make-errors-routes wire running-logbook settings))
        (into (meter/make-metrics-routes app-meter (:metrics settings)))
        (into (visitors/make-visitor-routes wire pool settings))
        (into (files/make-upload-routes running-attic wire log pool find-session-step may-upload? settings))
        (into (postbox/make-webhook-routes running-postbox (get-in settings [:postbox :webhooks])
                                           {:run-in-transaction #(doorman/run-as-system! pool postbox-system-actor {:read-only? false} %)
                                            :on-delivery-event  on-delivery-event
                                            :on-inbound-email   on-inbound-email}))
        (into (bridge/make-routes-for-messages
               handlers
               (:server settings)
               {:wire                wire
                :log                 log
                :meter               app-meter
                :find-rate-limit-key (make-rate-limit-key-finder settings)
                :on-sse-open  (fn [line]
                                (bridge/send-message-on-sse-line! line {:groundwork/version (:version settings)} nil)
                                (doorbell/add-sse-line! running-doorbell line))
                :on-sse-close #(doorbell/remove-sse-line! running-doorbell %)
                :on-ws-open   nil
                :on-ws-close  nil}))
        (into (page/make-catalog-routes (:languages settings)))
        (into (bridge/make-server-routes-for-pages pages (page/make-page-handler wire settings browser-settings))))))

(defn- is-watching? [settings]
  (map? (:browser-build settings)))

(defn- make-watch-options
  "showroom's start-watching! options: the :browser-build :watch settings,
   with the app's entry, the files folder and the app's log."
  [{:keys [browser-entry browser-modules settings]} log]
  (assoc (get-in settings [:browser-build :watch])
         :entry browser-entry
         :modules browser-modules
         :files-folder (get-in settings [:page :files :folder])
         :log log))

(defn- check-watch-options!
  "showroom checks the watch options, every key required, before anything
   starts: showroom's make-build checks them and builds nothing."
  [app log]
  ((requiring-resolve 'showroom.api/make-build) :watch (make-watch-options app log)))

(defn- start-watching-browser-build!
  "Loaded here, not required above: production has neither showroom nor
   shadow-cljs."
  [app log]
  ((requiring-resolve 'showroom.api/start-watching!) (make-watch-options app log)))

(defn- stop-watching-browser-build! [watching]
  ((requiring-resolve 'showroom.api/stop-watching!) watching))

(defn- start-part!
  "Starts one part, remembering how to stop it, so a failed start stops
   what had started, last first."
  [stoppers start stop]
  (let [part (start)]
    (swap! stoppers conj #(stop part))
    part))

(defn- check-modules-of-pages
  "Every page's :module, when it names one, is one of the app's
   :browser-modules; throws with every page that names another."
  [{:keys [pages browser-modules]}]
  (let [known    (set (map name (keys browser-modules)))
        problems (for [[page-name {:keys [module]}] pages
                       :when (and (some? module) (not (contains? known module)))]
                   (str page-name " names the module " (pr-str module) ", which :browser-modules doesn't have"))]
    (when (seq problems)
      (throw (ex-info (str "The pages have problems: " (pr-str (vec problems)))
                      {:type :groundwork.server.main/bad-pages :problems (vec problems)})))))

(def ^:private docs-of-parts
  "Each top settings key's shape var, whose docstring says what goes
   there, for study's :explain-setting; :settings the whole one's."
  {:settings        #'settings-shape
   :version         #'settings-shape
   :wire            #'wire/settings-shape
   :logbook         #'logbook/settings-shape
   :meter           #'meter/settings-shape
   :metrics         #'meter/routes-settings-shape
   :study           #'study/settings-shape
   :errors          #'errors-settings-shape
   :browser-wire    #'browser-wire-settings-shape
   :migrate-at-start? #'settings-shape
   :browser-build   #'browser-build-settings-shape
   :server          #'bridge/settings-shape
   :database        #'database/settings-shape
   :migration       #'migration-settings-shape
   :sessions        #'doorman/settings-shape
   :visitors        #'visitors-settings-shape
   :my-actions      #'my-actions-settings-shape
   :run-once        #'run-once-settings-shape
   :languages       #'dictionary/settings-shape
   :background-jobs #'backroom/settings-shape
   :attic           #'attic-settings-shape
   :postbox         #'postbox-settings-shape
   :doorbell        #'doorbell-settings-shape
   :page            #'page-settings-shape
   :health          #'health-settings-shape})

(defn- make-study-tools
  "groundwork's tools for the REPL (groundwork.server.main.tools), from the running
   app's parts."
  [{:keys [handlers tables scope-kinds settings] :as app} wire log pool]
  (tools/make-tools {:make-ready-handlers #(make-ready-handlers pool app %)
                     :wire                wire
                     :log                 log
                     :pool                pool
                     :handlers            handlers
                     :tables              tables
                     :scope-kinds         scope-kinds
                     :allowed-origin      (get-in settings [:sessions :allowed-origin])
                     :settings-shape      settings-shape
                     :docs-of-parts       docs-of-parts}))

(def ^:private app-functions-shape
  "The app map's functions for the files and the email, every key
   required: :may-upload?, (fn [asker] true-or-false), who may upload
   files; :on-delivery-event, {tag (fn [event {:keys [transaction log]}])},
   a bounce or spam complaint by its email's tag, {} for none;
   :on-inbound-email, {mailbox (fn [email {:keys [transaction log]}])}, an
   inbound email by its mailbox, {} for none. postbox checks the two maps'
   tags and functions. Each runs in a transaction as doorman's system
   actor :postbox (postbox-system-actor): the gates let it reach what the
   app's sentences give [:system :postbox], the open rows and the tables
   without sentences; nothing else."
  [:map
   [:may-upload? fn?]
   [:on-delivery-event map?]
   [:on-inbound-email map?]])

(defn- check-app-functions [app]
  (when-let [explanation (malli/explain app-functions-shape app)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The app has problems: " (pr-str errors))
                      {:type :groundwork.server.main/bad-app :errors errors})))))

(defn start-app!
  "Starts the app (see the namespace). Returns the running app, for stop-app!:
   {:settings :wire :logbook :log :meter :texts :pool :attic :postbox :handlers :doorbell :workers :watching :health :server :study},
   :attic the app's one attic (attic.api: store-file!, make-signed-link, …),
   :postbox the app's one postbox (postbox.api: queue-email! in a write's
   transaction),
   :texts every catalog that shows, read once (dictionary's load-texts),
   for the server's own texts: (dictionary/make-text texts \"de\"
   :mail/subject {:order 7}), the language a value (the request's, or the
   row's an email or a PDF is made for),
   :server holding the :port it listens on, :wire the app's one wire, made
   from the :wire settings, :logbook the app's one logbook, made from the
   :logbook settings, and :log its log function, for the app's own lines,
   :meter the app's one meter, made from the :meter settings, which every
   library counts on and the :metrics route serves (the app counts its own
   there too: backroom's add-background-job-to-queue! takes it), :study the
   REPL, started last (study's start-repl-server!, the :study settings,
   groundwork's tools in study.tools)."
  [{:keys [settings background-job-kinds schedules tables] :as app}]
  (check-settings settings)
  (check-app-functions app)
  (check-modules-of-pages app)
  (check-tables-leave-gates-and-on-change-out tables)
  (let [running-logbook (logbook/make-logbook (:logbook settings))
        log             (logbook/log-function running-logbook)]
    (when (is-watching? settings) (check-watch-options! app log))
    (let [wire     (wire/make-wire-from-settings (:wire settings))
          app-meter (meter/make-meter (:meter settings) {:log log})
          stoppers (atom ())]
      (try
        (let [texts             (dictionary/load-texts (:languages settings))
              pool              (start-part! stoppers
                                             #(database/start-pool! (:database settings) {:log log :meter app-meter})
                                             database/stop-pool!)
              _                 (if (:migrate-at-start? settings)
                                  @(:slow-part (install-functions-and-migrate! pool app log))
                                  (refuse-unsafe-database! pool app))
              running-attic     (start-part! stoppers
                                             #(attic/make-attic (get-in settings [:attic :storage]) {:log log :meter app-meter})
                                             attic/stop-attic!)
              running-postbox   (postbox/make-postbox (get-in settings [:postbox :sending]) {:log log :meter app-meter :wire wire})
              find-session-step (doorman/make-find-session-step pool (:sessions settings))
              handlers          (-> (make-ready-handlers pool app find-session-step)
                                    (errors/add-browser-error-handler find-session-step
                                                                      (get-in settings [:errors :browser-rate-limit])))
              running-doorbell  (start-part! stoppers
                                             #(doorbell/start-listening! pool (get-in settings [:doorbell :listening])
                                                                         {:log log :meter app-meter :tables (make-ready-tables app)})
                                             doorbell/stop-listening!)
              workers           (start-part! stoppers
                                             #(backroom/start-workers! pool (assoc (:background-jobs settings)
                                                                                   :wire wire
                                                                                   :log log
                                                                                   :meter app-meter
                                                                                   :kinds (conj (vec background-job-kinds)
                                                                                                (run-once/make-forget-keys-kind (:run-once settings))
                                                                                                (postbox/make-background-job-kind running-postbox))
                                                                                   :schedules (conj (vec schedules)
                                                                                                    (run-once/make-forget-keys-schedule (:run-once settings)))))
                                             backroom/stop-workers!)
              watching          (when (is-watching? settings)
                                  (start-part! stoppers #(start-watching-browser-build! app log) stop-watching-browser-build!))
              running-health    (health/make-health)
              _                 (metrics/declare-groundwork-metrics! app-meter running-logbook (:stage running-health) (:version settings))
              server            (start-part! stoppers
                                             #(bridge/start-server!
                                               (page/wrap-languages
                                                (bridge/make-ring-handler-from-routes
                                                 (make-routes app {:wire wire :running-logbook running-logbook :log log :app-meter app-meter
                                                                   :pool pool :running-health running-health :handlers handlers
                                                                   :running-doorbell running-doorbell :running-attic running-attic
                                                                   :running-postbox running-postbox :find-session-step find-session-step})
                                                 {:files (get-in settings [:page :files])})
                                                (:languages settings))
                                               (:server settings)
                                               {:log log})
                                             bridge/stop-server!)]
          (health/mark-stage! running-health :started)
          (log :info :groundwork/started {:groundwork/version (:version settings) :groundwork/port (:port server)})
          (let [repl (study/start-repl-server! (:study settings)
                                               {:log log :tools (make-study-tools app wire log pool)})]
            {:settings settings :wire wire :logbook running-logbook :log log :meter app-meter :texts texts :pool pool
             :attic running-attic :postbox running-postbox :handlers handlers :doorbell running-doorbell :workers workers :watching watching :health running-health
             :server server :study repl}))
        (catch Throwable e
          (doseq [stop @stoppers] (stop))
          (throw e))))))

(defn stop-app!
  "Closes the REPL first, then answers ready with 503 for the :health
   settings' :not-ready-before-stop-ms, then stops taking requests, stops
   the watcher, lets running background jobs finish, stops listening,
   stops the attic's client, closes the pool."
  [{:keys [settings log pool server workers watching] running-doorbell :doorbell running-health :health repl :study
    running-attic :attic}]
  (study/stop-repl-server! repl)
  (log :info :groundwork/stopping {:groundwork/version (:version settings)})
  (health/mark-stage! running-health :stopping)
  (Thread/sleep (long (get-in settings [:health :not-ready-before-stop-ms])))
  (bridge/stop-server! server)
  (when watching (stop-watching-browser-build! watching))
  (backroom/stop-workers! workers)
  (doorbell/stop-listening! running-doorbell)
  (attic/stop-attic! running-attic)
  (database/stop-pool! pool))
