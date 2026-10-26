(ns groundwork.server.main.api
  "The server's one start: the server libraries put together into a
   running app. The browser's start is groundwork.browser.main.

     (def app
       {:settings             (settings/load {:file  \"settings/production.edn\"   ; groundwork-settings's: #secret and #env filled,
                                          :shape main/settings-shape          ; checked against settings-shape
                                          :env   (System/getenv)})
        :browser-entry        'app.browser.main/start!  ; the function the page runs
        :browser-modules      {}                    ; browser-build's :modules, loaded on demand: {:admin {:entries [app.admin.page] :depends-on #{:main} :start nil}}
        :tables               [shops orders]        ; table maps, with :access and :live-updates
        :extensions           []                    ; groundwork-postgres's: the database's extensions
        :functions            []                    ; groundwork-postgres's: the .sql files of the app's functions
        :views                []                    ; groundwork-postgres's: the .sql files of its views, in order
        :changes              []                    ; groundwork-postgres's changes
        :scope-kinds          scope-kinds           ; groundwork-access's
        :handlers             handlers              ; bridge's table, each with :action, where it acts (:record or :scope), shapes and :daily-caps
        :pages                {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}
                               :admin/page  {:address \"/admin\" :title \"Admin\" :module \"admin\"}}   ; loaded on the way in
        :background-job-kinds [send-invoice-email]  ; groundwork-background-jobs's kinds, each naming its :system-actor
        :schedules            []
        :given-to-every-background-job-run {}       ; groundwork-background-jobs's :given-to-every-run: each run's :given
        :may-upload?          (fn [{:keys [person-id]}] (some? person-id))   ; who may upload files, the asker
        :on-delivery-event    {:orders/confirmation mark-address-bounced!} ; groundwork-email's, by the email's tag
        :on-inbound-email     {\"quotes\" add-quote-request!}})           ; groundwork-email's, by the mailbox

     (def running (main/start-app! app))
     (main/stop-app! running)

   The settings are one EDN file per environment, loaded by groundwork-settings: every
   secret in them (the database's password, the :errors and :metrics
   tokens, the bucket's keys, Postmark's and the webhooks' tokens) is
   groundwork-settings's, which prints as its name, revealed only where it is used; text
   written out where a secret belongs is refused.

   start-app! checks the settings (every problem at once), makes the app's
   one shaper from the :shaped-json settings (groundwork-shaped-json.api's make-shaper-from-settings)
   and passes it to every library that sends or reads data across the
   app's edges (bridge's routes, groundwork-background-jobs's workers, the page, the
   health checks; no library makes its own), makes the app's one logger
   from the :logs settings and passes its log to every library that
   writes a line (groundwork-postgres's migrate, groundwork-live-updates's listener, groundwork-background-jobs's
   workers, browser-build's watcher, bridge's routes and server, the health
   checks; each message's steps get one with its request id, the person
   added once groundwork-access finds them), puts groundwork's own tables with
   the app's, migrates in development (:migrate-at-start?
   true) or else refuses a database that isn't current or a role that could
   pass the gates, then starts the pool, the handlers (groundwork-access's check,
   groundwork's :groundwork-access/my-actions, then groundwork-live-updates's notes; every write keyed
   to run once, groundwork.server.main.run-once; every handler's
   :daily-caps counted after groundwork-access's check, groundwork.server.main.daily-caps), groundwork-live-updates's listener, the
   background job workers (groundwork's own that forgets old one-time keys
   among them; each job's one transaction is groundwork-access's run-as-system! as
   its kind's :system-actor, so it reaches what that actor's sentences
   give it), in development the browser
   build's watcher (:browser-build {:watch options}, with the app's
   :browser-modules), and the server (bridge's three addresses, from
   :bridge :addresses, every page answered by the page shell in the
   request's language, the app's own :routes, files from :page :files; no
   ws hooks). Every
   request first gets its language (groundwork-languages's, from the :languages
   settings) and loses its address's language prefix, for routing, and is
   handled with that language bound: (languages/tr key args) in a
   handler is in the request's language. Each
   language's catalog is read at the start (a missing one refuses it) and
   served where the browser fetches it, /<catalogs-path>/<language>.json.
   Every reason a refusal may give (groundwork-steps's, groundwork-access's, the run-once
   ones, every handler's :refusals) needs its text in every catalog: one
   missing refuses the start (groundwork-languages's check-texts-of-reasons). A page
   may name a :module of :browser-modules, loaded on demand by the browser.

   In production the deploy runs migrate-app! first, as the role that owns
   the tables (the app's own role owns nothing), and the browser's release
   build (browser-build, its own library), whose files the page lists
   (:browser-build :built). Production has neither browser-build nor
   shadow-cljs: browser-build is loaded only when it watches.

   Two GETs answer whoever runs the app, outside sessions, access and rate
   limits (groundwork.server.main.health, the :health settings): live, 200
   while the process answers; ready, 200 once started while the database
   answers, else 503, and 503 from the start of stop-app!.

   One more GET, for a monitor, outside them too (groundwork.server.main.errors,
   the :errors settings): the :error lines groundwork-logs keeps, after ?since=N,
   only with the settings' bearer token. And one handler of groundwork's own,
   :groundwork/browser-error: the browser's errors, uncaught or caught by
   an app's error boundary, sent by groundwork.browser.main with their
   kind and the bundle's version, written at :error as :browser/error, so
   they are kept with the server's, the one list a monitor reads; its rate
   limit is the :errors settings'.
   And one of groundwork-access's, :groundwork-access/my-actions (make-my-actions-handler):
   the asker's actions by scope, and the open ones under nil kind and id,
   which the browser's start names as groundwork-screens's :actions-question; its rate
   limit is the :my-actions settings'.

   One meter (groundwork-metrics's make-meter, from the :metrics settings) is made once
   and handed to every library that counts (groundwork-postgres's pool, bridge's
   routes, groundwork-live-updates's listener, groundwork-background-jobs's workers), with groundwork's own
   (the stage, the ready checks, the version) and groundwork-logs's counts, read
   when written (groundwork.server.main.metrics). Its text is one more GET, at
   the :metrics settings' :route :path, beside health and errors, outside them
   too, only with its bearer token (groundwork-metrics's make-metrics-routes).

   A visitor's session (groundwork-access's start-visitor-session!) is a POST at the
   :visitors settings' :path, from the app's own pages only, rate-limited
   by the asker's address (:visitors :rate-limit, 429 over it;
   groundwork.server.main.visitors).

   The REPL (groundwork-live-repl's start-repl-server!, the :live-repl settings) starts last
   and stops first, with groundwork's tools in groundwork-live-repl.tools:
   :ask-as-person, :why-refused and :explain-setting
   (groundwork.server.main.tools).

   A table map of the app's says :access and :live-updates, never :gates or
   :on-change, which groundwork-access and groundwork-live-updates put on it: one that writes
   either is refused at the start, named.

   The app's files (groundwork-file-storage's make-file-storage, the :file-storage :storage settings)
   and its email (groundwork-email's make-mailer, the :email :sending settings)
   are made once, on the app's log and meter (and groundwork-shaped-json, groundwork-email's), and
   held by the running app (:file-storage, :email); their tables (groundwork_file_storage.files,
   groundwork_email.emails) are migrated with groundwork's, groundwork-email's background job
   kind runs with the app's. Files are uploaded at the :file-storage :upload
   :path (groundwork.server.main.files: groundwork-access's session step, then the app's
   :may-upload?, (fn [asker])); the app deletes a file with groundwork-file-storage's
   delete-file! on (:file-storage running) and (:pool running), after its own
   rows are kept, or in a handler's step with delete-file-after-commit!
   and the step's :groundwork-access/transaction, run once it commits (groundwork-postgres's
   run-after-commit!) (only a file no row names through a reference to
   :file/key goes). The provider's webhooks are groundwork-email's, at the :email
   :webhooks paths, handing events to the app's :on-delivery-event (by
   tag) and :on-inbound-email (by mailbox), each run as groundwork-access's system
   actor :email, which the app's sentences give what it may do:
   {:allow :shop-emails/mark-bounced :only :all-of-the-action :to [:system :email]}."
  (:require
   [clojure.string :as str]
   [groundwork-file-storage.api :as file-storage]
   [groundwork-background-jobs.api :as background-jobs]
   [groundwork-postgres.api :as postgres]
   [groundwork-languages.api :as languages]
   [groundwork-access.api :as access]
   [groundwork-live-updates.api :as live-updates]
   [groundwork.server.main.answer-as :as answer-as]
   [groundwork.server.main.app-routes :as app-routes]
   [groundwork.server.main.daily-caps :as daily-caps]
   [groundwork.server.main.errors :as errors]
   [groundwork.server.main.files :as files]
   [groundwork.server.main.health :as health]
   [groundwork.server.main.metrics :as metrics]
   [groundwork.server.main.page :as page]
   [groundwork.server.main.run-once :as run-once]
   [groundwork.server.main.tools :as tools]
   [groundwork.server.main.visitors :as visitors]
   [groundwork-logs.api :as logs]
   [groundwork-bridge.api :as bridge]
   [groundwork-steps.api :as steps]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [groundwork-metrics.api :as groundwork-metrics]
   [groundwork-email.api :as email]
   [groundwork-settings.api :as settings]
   [groundwork-live-repl.api :as live-repl]
   [groundwork-shaped-json.api :as shaped-json]))

(def page-settings-shape
  "The :page part of the settings, every key required: the page shell's
   details and the files served beside it. Its lang is the request's
   language (the :languages settings).
   :viewport: its viewport meta, \"width=device-width, initial-scale=1\".
   :content-security-policy: :none for no header, or {:policy text
   :nonce? bool}: with :nonce? true each page answer gets a nonce of its
   own, put in place of {nonce} in the policy (which must name it), on the
   shell's scripts, and handed to the app's :render-page.
   :referrer-policy: the header, \"same-origin\".
   :frame-options: the X-Frame-Options header, \"DENY\" or \"SAMEORIGIN\",
   or :none when other sites may frame the page.
   :files: bridge's files: :folder, where the browser build is (the
   watcher writes it, the release build's manifests are read from it, a
   GET no route matches is answered from it); :hashed-name-min-hex-digits,
   how many hex digits make a name hashed (main.3F9A0C1D.js);
   :hashed-file-cache-seconds, how long a browser keeps a hashed file."
  [:map {:closed true}
   [:viewport :string]
   [:content-security-policy [:or [:= :none]
                              [:and
                               [:map {:closed true} [:policy :string] [:nonce? :boolean]]
                               [:fn {:error/message ":policy names {nonce} when :nonce? is true, and only then"}
                                (fn [{:keys [policy nonce?]}] (= (boolean nonce?) (str/includes? policy "{nonce}")))]]]]
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
   each part of a migration runs under (groundwork-postgres's migrate! options), in
   place of the pool's. :none is no limit.
   :instant-part: the catalog changes, in one transaction before the app
   starts; a :lock-timeout-ms keeps an ALTER queued behind a long
   transaction from holding every query to its table.
   :slow-part: the index builds and validations over every row, after."
  [:map {:closed true}
   [:instant-part part-limits-shape]
   [:slow-part part-limits-shape]])

(def live-updates-settings-shape
  "The :live-updates part of the settings, every key required, both groundwork-live-updates's:
   :store, the browser's question store's, which the page carries
   (groundwork-live-updates's store-settings-shape); :listening, the server's listener's
   (groundwork-live-updates's listening-settings-shape)."
  [:map {:closed true}
   [:store live-updates/store-settings-shape]
   [:listening live-updates/listening-settings-shape]])

(defn- leaves-entry-folder-and-log-out? [watch-options]
  (not-any? #(contains? watch-options %) [:entry :modules :files-folder :log]))

(def browser-build-settings-shape
  "The :browser-build setting: :built, the release build's files, listed
   by their manifests; or {:watch options}, development, browser-build's
   watcher started with these options: every one of browser-build's
   start-watching! options but :entry, :modules, :files-folder and :log,
   which come from the app's :browser-entry and :browser-modules, the
   :page :files :folder and the app's log. browser-build checks them, every key required, before the app starts
   anything."
  [:or
   [:= :built]
   [:map {:closed true}
    [:watch [:and
             [:map-of :keyword :any]
             [:fn {:error/message ":entry, :modules, :files-folder and :log come from :browser-entry, :browser-modules, :page :files :folder and the app's log"}
              leaves-entry-folder-and-log-out?]]]]])

(def browser-shaped-json-settings-shape
  "The :browser-shaped-json part of the settings, every key required: the
   browser's groundwork-shaped-json settings (groundwork-shaped-json's settings-shape in the browser) but
   :exact-decimals, a function, which the app's browser start hands to
   groundwork.browser.main's start-app!. The page carries them.
   :most-shapes-kept: how many shapes' tools the browser's groundwork-shaped-json keeps
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
   secret of groundwork-settings's (#secret :errors/token in the app's settings file);
   text written out is refused.
   :browser-rate-limit: how many browser errors one asker may send,
   {:max 10 :per-ms 60000}; more are answered \"Too many requests.\" and
   not written."
  [:map {:closed true}
   [:path :string]
   [:token settings/secret]
   [:browser-rate-limit [:map {:closed true}
                         [:max [:int {:min 1}]]
                         [:per-ms [:int {:min 1}]]]]])

(def my-actions-settings-shape
  "The :my-actions part of the settings, every key required: groundwork's
   :groundwork-access/my-actions handler (groundwork-access's make-my-actions-handler), which
   the browser asks for groundwork-screens's \"can I?\".
   :rate-limit: how many times one asker may ask, {:max 60 :per-ms
   60000}, or nil for no limit; more are answered \"Too many requests.\"."
  [:map {:closed true}
   [:rate-limit [:maybe [:map {:closed true}
                         [:max [:int {:min 1}]]
                         [:per-ms [:int {:min 1}]]]]]])

(def visitors-settings-shape
  "The :visitors part of the settings, every key required: the route that
   gives a visitor (someone not signed in) a session of their own,
   groundwork-access's start-visitor-session! (groundwork.server.main.visitors).
   :path: the POST the browser sends the first time the app keeps
   something for a visitor, \"/groundwork/visitor\"; the page carries it to
   groundwork.browser.main's start-visitor-session!.
   :rate-limit: how many times one asker, told apart by their address
   (the :bridge settings' :client-address-header behind a proxy), may POST
   it, {:max 10 :per-ms 60000}; more are answered 429 and make no session."
  [:map {:closed true}
   [:path [:re #"^/"]]
   [:rate-limit [:map {:closed true}
                 [:max [:int {:min 1}]]
                 [:per-ms [:int {:min 1}]]]]])

(def file-storage-settings-shape
  "The :file-storage part of the settings, every key required, both groundwork-file-storage's:
   :storage, the bucket's (groundwork-file-storage's settings-shape: :endpoint :bucket
   :access-key :secret-key :region :public-url :link-expiry-seconds
   :connect-timeout-ms :request-timeout-ms :retries), which groundwork makes
   the app's one file storage from; :upload, the upload route's: :path, the POST
   the browser sends files to, \"/groundwork/files\", and groundwork-file-storage's upload
   settings (groundwork-file-storage's upload-settings-shape: :key-prefix
   :allowed-content-types :max-file-bytes :max-files). :max-file-bytes
   must be below the :bridge settings' :max-body-bytes, which holds the
   whole upload."
  [:map {:closed true}
   [:storage file-storage/settings-shape]
   [:upload (into [:map {:closed true} [:path [:re #"^/"]]] (drop 2 file-storage/upload-settings-shape))]])

(def email-settings-shape
  "The :email part of the settings, every key required, both groundwork-email's:
   :sending, the mailer's (groundwork-email's settings-shape: :provider, :postmark
   or :log, :tags, :background-job), which groundwork makes the app's one
   groundwork-email from; :webhooks, the provider's two routes (groundwork-email's
   webhook-settings-shape: :delivery-path :inbound-path :token
   :inbound-domain)."
  [:map {:closed true}
   [:sending email/settings-shape]
   [:webhooks email/webhook-settings-shape]])

(def daily-caps-settings-shape
  "The :daily-caps part of the settings, every key required: the
   handlers' :daily-caps, counted in Postgres (groundwork.server.main.daily-caps).
   :pool-size: the connections of the pool the counts run on, a pool of
   their own (the :postgres settings, but this size), so a handler's
   transaction never waits for a second connection of the app's pool;
   its metrics are on a meter of its own, not served.
   :forget-counts: groundwork's background job that deletes the counts of
   the days before today: :cron, when it runs (UTC, groundwork-background-jobs's cron line,
   \"15 0 * * *\"); :timeout-ms and :attempts, groundwork-background-jobs's for its kind."
  [:map {:closed true}
   [:pool-size [:int {:min 1}]]
   [:forget-counts [:map {:closed true}
                    [:cron :string]
                    [:timeout-ms [:int {:min 1}]]
                    [:attempts [:int {:min 1}]]]]])

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
   :cron, when it runs (UTC, groundwork-background-jobs's cron line, \"*/15 * * * *\");
   :timeout-ms and :attempts, groundwork-background-jobs's for its kind.
   :retry-writes: the browser's, which the page carries to groundwork-screens: a write
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
   :shaped-json is groundwork-shaped-json's settings on the JVM (groundwork-shaped-json.api/settings-shape), which
   groundwork makes the app's one shaper from; :browser-shaped-json the browser's.
   :logs is groundwork-logs's settings (groundwork-logs.api/settings-shape), which
   groundwork makes the app's one logger from; :errors where its kept errors
   are pulled, and how often a browser may send one; :my-actions how often
   a browser may ask what its person may do; :run-once how long a write's
   one-time key is kept, and how often a browser retries a write;
   :daily-caps the pool the handlers' daily caps are counted on, and the
   job that forgets old counts;
   :languages the app's languages (groundwork-languages's settings), which a
   request's, and its page's, is picked from; :metrics the app's one
   groundwork-metrics's (groundwork-metrics.api/settings-shape: :jvm-metrics, and
   :most-series-per-metric, which must cover the largest declaration:
   bridge's messages_total, (handlers+1)×24, its answer_seconds,
   (handlers+1)×(buckets+3), groundwork-postgres's query_seconds, (queries+1)×(buckets+3),
   the handlers counting groundwork's two and the queries every library's)
   and its :route, where Prometheus pulls its text (groundwork-metrics.api/routes-settings-shape:
   :path and :token); :live-repl the REPL's (groundwork-live-repl.api/settings-shape:
   :development, :ssh-only or :off with the emergency switch); :visitors
   where a visitor's session is started; :file-storage the app's files, the
   bucket and the upload route; :email its email, the provider and the
   webhooks."
  [:map {:closed true}
   [:version :string]
   [:shaped-json shaped-json/settings-shape]
   [:logs logs/settings-shape]
   [:metrics (into groundwork-metrics/settings-shape [[:route groundwork-metrics/routes-settings-shape]])]
   [:live-repl live-repl/settings-shape]
   [:errors errors-settings-shape]
   [:browser-shaped-json browser-shaped-json-settings-shape]
   [:migrate-at-start? :boolean]
   [:browser-build browser-build-settings-shape]
   [:bridge bridge/settings-shape]
   [:postgres postgres/settings-shape]
   [:migration migration-settings-shape]
   [:access access/settings-shape]
   [:visitors visitors-settings-shape]
   [:my-actions my-actions-settings-shape]
   [:run-once run-once-settings-shape]
   [:daily-caps daily-caps-settings-shape]
   [:languages languages/settings-shape]
   [:background-jobs background-jobs/settings-shape]
   [:file-storage file-storage-settings-shape]
   [:email email-settings-shape]
   [:live-updates live-updates-settings-shape]
   [:page page-settings-shape]
   [:health health-settings-shape]])

(defn- throw-bad-settings [errors]
  (throw (ex-info (str "The settings have problems: " (pr-str errors))
                  {:type :groundwork.server.main/bad-settings :errors errors})))

(defn check-settings
  "Returns the settings; throws with every problem at once. Checked by
   malli itself: the settings are values the app hands in, and the shaper is
   made from them only once they fit. Then across parts: one uploaded
   file fits in the server's body limit."
  [settings]
  (when-let [explanation (malli/explain settings-shape settings)]
    (throw-bad-settings (malli-error/humanize explanation)))
  (when (>= (get-in settings [:file-storage :upload :max-file-bytes]) (get-in settings [:bridge :max-body-bytes]))
    (throw-bad-settings {:file-storage {:upload {:max-file-bytes ["should be below the :bridge settings' :max-body-bytes, which holds the whole upload"]}}}))
  settings)

(def groundwork-tables
  "groundwork-access's tables, groundwork-background-jobs's, groundwork-file-storage's and groundwork-email's, each in their own
   folder, and groundwork's own one-time keys (groundwork.run_once_keys) and
   daily caps' counts (groundwork.daily_cap_counts), put with the app's. None
   knows of groundwork-live-updates; their live updates are said here: the sessions, the
   tokens, the background jobs, the files, the emails, the keys and the
   counts silent; groundwork-access's access tables tell, so a screen's
   :groundwork-access/my-actions is asked again when a membership or a role
   changes."
  (vec (concat [(assoc run-once/run-once-keys-table :live-updates [:silent "no screen shows one-time keys"])
                (assoc daily-caps/daily-cap-counts-table :live-updates [:silent "no screen shows the daily caps' counts"])]
               (map #(assoc % :live-updates [:silent "no screen shows sessions"]) [access/sessions-table])
               (map #(assoc % :live-updates [:silent "groundwork-access's list-tokens reads it off a message's transaction: no screen's notes name it"]) [access/tokens-table])
               (map #(assoc % :live-updates :tell) access/access-tables)
               (map #(assoc % :live-updates [:silent "no screen shows background jobs"]) background-jobs/background-jobs-tables)
               [(assoc file-storage/files-table :live-updates [:silent "a screen shows the app's rows that name a file, not groundwork-file-storage's"])
                (assoc email/emails-table :live-updates [:silent "no screen shows groundwork-email's emails"])])))

(defn- check-tables-leave-gates-and-on-change-out
  "The app's table maps say :access and :live-updates, never :gates or
   :on-change: those are what groundwork-access and groundwork-live-updates put on them from
   :access and :live-updates, and a table's own would be overwritten without
   a word. Throws naming every table that writes either, as groundwork-access
   refuses a handler's own :run-steps-in."
  [tables]
  (let [problems (for [{:keys [table] :as table-map} tables
                       taken [:gates :on-change]
                       :when (contains? table-map taken)]
                   (str table " writes its own " taken ", which groundwork puts on it: "
                        (if (= :gates taken) "groundwork-access's, from its :access" "groundwork-live-updates's, from its :live-updates")))]
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
  "The app's tables and groundwork's, with their gates (groundwork-access) and change
   triggers (groundwork-live-updates): what migrate brings Postgres to. An app's table
   that writes its own :gates or :on-change is refused, named."
  [{:keys [scope-kinds] :as app}]
  (-> (list-all-tables app)
      (access/put-gates-on-tables scope-kinds)
      (live-updates/put-live-updates-on-tables)))

(def ^:private app-database-shape
  "The app map's database objects beside its tables, as groundwork-postgres declares
   them, every key required: :extensions, the extensions' names, [] for
   none; :functions, the .sql files on the classpath of the app's own
   functions, one CREATE OR REPLACE FUNCTION each, [] for none; :views,
   the .sql files of its views, one CREATE VIEW each, in the order they
   are made, [] for none. groundwork-postgres drops a function or a view in the
   tables' folders that nothing declared makes."
  [:map
   [:extensions [:sequential :string]]
   [:functions [:sequential :string]]
   [:views [:sequential :string]]])

(defn- check-app-database [app]
  (when-let [explanation (malli/explain app-database-shape app)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The app has problems: " (pr-str errors))
                      {:type :groundwork.server.main/bad-app :errors errors})))))

(defn- make-declared
  "What migrate brings Postgres to, as groundwork-postgres takes it: the ready tables,
   the app's extensions and views, and its functions with groundwork-access's and
   groundwork-live-updates's own (their function-files) and those groundwork-access makes from
   the sentences (make-access-functions), declared to groundwork-postgres as made
   functions, so it makes them before the gates and drops one whose
   sentence is gone."
  [{:keys [scope-kinds] :as app}]
  {:tables     (make-ready-tables app)
   :extensions (:extensions app)
   :functions  (-> (vec access/function-files)
                   (into (access/make-access-functions (list-all-tables app) scope-kinds))
                   (into live-updates/function-files)
                   (into (:functions app)))
   :views      (:views app)})

(defn- migrate! [pool app log]
  (postgres/migrate! pool (make-declared app) (:changes app) (assoc (get-in app [:settings :migration]) :log log)))

(defn migrate-app!
  "For the deploy, before the app starts: as the role that owns the tables
   (owner-db-settings), brings Postgres to the tables, the app's extensions,
   functions and views (groundwork-access's and groundwork-live-updates's own functions with them,
   and those groundwork-access makes from the sentences)
   and changes (waiting for the slow part too), each part under
   the :migration settings' time limits, and lets the app's own role
   (app-role) use them. Each planned statement is written to a log made
   from the :logs settings (:groundwork-postgres/planned). The owner's pool counts
   on a meter of its own, never served (:jvm-metrics false): groundwork-postgres takes
   one meter per pool, and the deploy's run has no /metrics."
  [app owner-db-settings app-role]
  (check-app-database app)
  (let [settings (:settings app)
        log      (logs/log-function (logs/make-logger (:logs settings)))
        meter    (groundwork-metrics/make-meter {:jvm-metrics            false
                                    :most-series-per-metric (get-in settings [:metrics :most-series-per-metric])}
                                   {:log log})
        pool     (postgres/start-pool! owner-db-settings {:log log :meter meter})]
    (try
      (deref (:slow-part (migrate! pool app log)))
      (postgres/grant-tables-to-role! pool (make-ready-tables app) app-role)
      (finally (postgres/stop-pool! pool)))))

(def ^:private find-powers-of-role
  (postgres/make-query-from-sql-file "groundwork/server/main/find_powers_of_role.sql"))

(defn- find-folders [tables]
  (set (map #(or (some-> (namespace (:table %)) (str/replace "-" "_")) "public") tables)))

(defn- refuse-unsafe-database! [pool app]
  (let [tables (make-ready-tables app)
        {:keys [passes-gates owns-tables]} (postgres/find-one-row pool find-powers-of-role
                                                                  {:folders (str/join "," (find-folders tables))})]
    (when (or passes-gates owns-tables)
      (throw (ex-info "The app's database role owns tables or passes the gates; it must own nothing (run migrate-app! as the owner)"
                      {:type :groundwork.server.main/role-too-strong})))
    (when-not (postgres/does-database-match-tables? pool (make-declared app) (:changes app))
      (throw (ex-info "The database isn't current: run migrate-app! first"
                      {:type :groundwork.server.main/not-migrated})))))

(defn- add-my-actions-handler
  "The handlers with groundwork's :groundwork-access/my-actions, groundwork-access's
   make-my-actions-handler: the asker's actions for the browser (groundwork-screens's
   \"can I?\"). An app's own handler of that name is refused."
  [handlers pool {:keys [tables scope-kinds settings]} find-session-step]
  (when (contains? handlers :groundwork-access/my-actions)
    (throw (ex-info "The handler name :groundwork-access/my-actions is groundwork's own; the app's handlers use it"
                    {:type :groundwork.server.main/handler-name-taken :handler :groundwork-access/my-actions})))
  (assoc handlers :groundwork-access/my-actions
         (access/make-my-actions-handler {:pool              pool
                                           :tables            tables
                                           :scope-kinds       scope-kinds
                                           :find-session-step find-session-step
                                           :rate-limit        (get-in settings [:my-actions :rate-limit])})))

(defn- make-ready-handlers [pool counting-pool {:keys [handlers tables scope-kinds settings] :as app} find-session-step]
  (-> handlers
      (daily-caps/put-daily-caps-into-handlers counting-pool (get-in settings [:bridge :client-address-header]))
      (run-once/put-run-once-into-handlers scope-kinds (get-in settings [:run-once :keep-keys-ms]))
      (access/put-access-into-handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                         :find-session-step find-session-step})
      (add-my-actions-handler pool app find-session-step)
      (live-updates/put-table-notes-into-handlers)))

(defn- make-all-ready-handlers
  "The handlers the server answers: the app's made ready, and
   :groundwork/browser-error, with find-session-step. The REPL's
   ask-as-person makes the same names with its own step, so every
   answered message is counted under one table of names on the app's
   meter (bridge declares its metrics by the names)."
  [pool counting-pool {:keys [settings] :as app} find-session-step]
  (-> (make-ready-handlers pool counting-pool app find-session-step)
      (errors/add-browser-error-handler find-session-step (get-in settings [:errors :browser-rate-limit]))))

(defn- make-rate-limit-key-finder
  "Askers told apart by their session (a hash of its cookie), else by
   their address, the proxy's header when the settings name one."
  [settings]
  (fn [request]
    (or (access/find-session-key-of-request request (:access settings))
        (bridge/find-client-address request (get-in settings [:bridge :client-address-header])))))

(defn- check-texts-of-reasons!
  "Every reason a refusal may give has its text in every catalog that
   shows (groundwork-languages's check-texts-of-reasons): groundwork-steps's (the ones
   every answer may carry, which groundwork-bridge gives), groundwork-access's,
   groundwork's run-once ones, and every ready handler's :refusals (the
   app's, with groundwork-access's and groundwork's added); the start is refused,
   :groundwork-languages/missing-texts, with every missing one at once."
  [texts ready-handlers]
  (languages/check-texts-of-reasons texts (apply merge steps/refusals access/refusals run-once/refusals
                                                  (map :refusals (vals ready-handlers)))))

(defn- run-as-system-actor!
  "groundwork-background-jobs's :run-in-transaction-as: a job's one transaction as its
   kind's :system-actor, groundwork-access's run-as-system!, read and write,
   carrying the job's log (work kept with run-after-commit! that fails is
   written there, with the job's id and kind)."
  [pool system-actor log f]
  (access/run-as-system! pool system-actor {:read-only? false :log log} f))

(def ^:private email-system-actor
  "The system actor groundwork-email's webhook functions run as (groundwork-access's
   run-as-system!): the app's sentences give it what they may do,
   {:allow :shop-emails/mark-bounced :only :all-of-the-action :to [:system :email]}.
   groundwork-email's sending job runs as it too (make-mailer's :system-actor): it
   reaches groundwork_email.emails, a table without sentences."
  :email)

(defn- make-routes [{:keys [settings pages may-upload? on-delivery-event on-inbound-email routes tables scope-kinds]}
                    {:keys [shaper running-logger log app-meter pool running-health handlers running-live-updates
                            running-file-storage running-mailer find-session-step answer-as page-handler]}]
  (-> (health/make-health-routes shaper log app-meter pool running-health settings)
      (into (errors/make-errors-routes shaper running-logger settings))
      (into (groundwork-metrics/make-metrics-routes app-meter (get-in settings [:metrics :route])))
      (into (visitors/make-visitor-routes shaper pool settings))
      (into (files/make-upload-routes running-file-storage shaper log pool find-session-step may-upload? settings))
      (into (email/make-webhook-routes running-mailer (get-in settings [:email :webhooks])
                                         {:run-in-transaction #(access/run-as-system! pool email-system-actor {:read-only? false :log log} %)
                                          :on-delivery-event  on-delivery-event
                                          :on-inbound-email   on-inbound-email}))
      (into (bridge/make-routes-for-messages
             handlers
             (:bridge settings)
             {:shaper                shaper
              :log                 log
              :meter               app-meter
              :find-rate-limit-key (make-rate-limit-key-finder settings)
              :on-sse-open  (fn [line]
                              (bridge/send-message-on-sse-line! line {:groundwork/version (:version settings)} nil)
                              (live-updates/add-sse-line! running-live-updates line))
              :on-sse-close #(live-updates/remove-sse-line! running-live-updates %)
              :on-ws-open   nil
              :on-ws-close  nil}))
      (into (app-routes/make-app-routes routes {:pool                        pool
                                                :log                         log
                                                :settings                    settings
                                                :tables                      tables
                                                :scope-kinds                 scope-kinds
                                                :find-session-step           find-session-step
                                                :find-person-rate-limit-key (make-rate-limit-key-finder settings)
                                                :answer-as                   answer-as}))
      (into (page/make-catalog-routes (:languages settings)))
      (into (bridge/make-server-routes-for-pages pages page-handler))))

(defn- is-watching? [settings]
  (map? (:browser-build settings)))

(defn- make-watch-options
  "browser-build's start-watching! options: the :browser-build :watch settings,
   with the app's entry, the files folder and the app's log."
  [{:keys [browser-entry browser-modules settings]} log]
  (assoc (get-in settings [:browser-build :watch])
         :entry browser-entry
         :modules browser-modules
         :files-folder (get-in settings [:page :files :folder])
         :log log))

(defn- check-watch-options!
  "browser-build checks the watch options, every key required, before anything
   starts: browser-build's make-build checks them and builds nothing."
  [app log]
  ((requiring-resolve 'groundwork-browser-build.api/make-build) :watch (make-watch-options app log)))

(defn- start-watching-browser-build!
  "Loaded here, not required above: production has neither browser-build nor
   shadow-cljs."
  [app log]
  ((requiring-resolve 'groundwork-browser-build.api/start-watching!) (make-watch-options app log)))

(defn- stop-watching-browser-build! [watching]
  ((requiring-resolve 'groundwork-browser-build.api/stop-watching!) watching))

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
   there, for groundwork-live-repl's :explain-setting; :settings the whole one's."
  {:settings        #'settings-shape
   :version         #'settings-shape
   :shaper            #'shaped-json/settings-shape
   :logs         #'logs/settings-shape
   :metrics         #'groundwork-metrics/settings-shape
   :live-repl           #'live-repl/settings-shape
   :errors          #'errors-settings-shape
   :browser-shaped-json    #'browser-shaped-json-settings-shape
   :migrate-at-start? #'settings-shape
   :browser-build   #'browser-build-settings-shape
   :bridge          #'bridge/settings-shape
   :postgres        #'postgres/settings-shape
   :migration       #'migration-settings-shape
   :access        #'access/settings-shape
   :visitors        #'visitors-settings-shape
   :my-actions      #'my-actions-settings-shape
   :run-once        #'run-once-settings-shape
   :daily-caps      #'daily-caps-settings-shape
   :languages       #'languages/settings-shape
   :background-jobs #'background-jobs/settings-shape
   :file-storage           #'file-storage-settings-shape
   :email         #'email-settings-shape
   :live-updates        #'live-updates-settings-shape
   :page            #'page-settings-shape
   :health          #'health-settings-shape})

(defn- make-live-repl-tools
  "groundwork's tools for the REPL (groundwork.server.main.tools), from the running
   app's parts."
  [{:keys [handlers tables scope-kinds settings] :as app} shaper log app-meter pool counting-pool]
  (tools/make-tools {:make-ready-handlers #(make-all-ready-handlers pool counting-pool app %)
                     :shaper                shaper
                     :log                 log
                     :meter               app-meter
                     :metric-buckets      (get-in settings [:bridge :metric-buckets])
                     :pool                pool
                     :handlers            handlers
                     :tables              tables
                     :scope-kinds         scope-kinds
                     :settings-shape      settings-shape
                     :docs-of-parts       docs-of-parts}))

(def ^:private app-functions-shape
  "The app map's own routes and its functions for the files and the
   email, every key required: :routes, the app's own server routes,
   records with a kind (groundwork.server.main.app-routes), {} for none;
   :render-page, (fn [request page {:keys [nonce asker run-as-asker!]}]),
   each page's title, description, head and body, and the not-found page
   (page nil), or a redirect (groundwork.server.main.page);
   :may-upload?, (fn [asker] true-or-false), who may upload
   files; :on-delivery-event, {tag (fn [event {:keys [transaction log]}])},
   a bounce or spam complaint by its email's tag, {} for none;
   :on-inbound-email, {mailbox (fn [email {:keys [transaction log]}])}, an
   inbound email by its mailbox, {} for none;
   :given-to-every-background-job-run, a map, {} for none: what every run
   of the app's background job kinds finds in its context as :given
   (groundwork-background-jobs's :given-to-every-run), the same map each
   time; groundwork's own kinds read nothing from it. groundwork-email checks the two maps'
   tags and functions. Each runs in a transaction as groundwork-access's system
   actor :email (email-system-actor): the gates let it reach what the
   app's sentences give [:system :email], the open rows and the tables
   without sentences; nothing else."
  [:map
   [:routes app-routes/routes-shape]
   [:render-page fn?]
   [:may-upload? fn?]
   [:on-delivery-event map?]
   [:on-inbound-email map?]
   [:given-to-every-background-job-run map?]])

(defn- check-app-functions [app]
  (when-let [explanation (malli/explain app-functions-shape app)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The app has problems: " (pr-str errors))
                      {:type :groundwork.server.main/bad-app :errors errors})))))

(defn start-app!
  "Starts the app (see the namespace). Returns the running app, for stop-app!:
   {:settings :shaper :logger :log :meter :texts :pool :counting-pool :file-storage :mailer :handlers :live-updates :workers :watching :health :server :live-repl},
   :counting-pool the pool the daily caps are counted on (the :daily-caps settings),
   :file-storage the app's one file storage (groundwork-file-storage.api: store-file!, make-signed-link, …),
   :mailer the app's one mailer (groundwork-email.api: queue-email! in a write's
   transaction),
   :texts every catalog that shows, read once (groundwork-languages's load-texts),
   for the server's own texts outside a request: an email, a PDF or a job
   binds the language of its row, (languages/with-language texts \"de\"
   (languages/tr :mail/subject {:order 7})),
   :server holding the :port it listens on, :shaper the app's one shaper, made
   from the :shaped-json settings, :logger the app's one logger, made from the
   :logs settings, and :log its log function, for the app's own lines,
   :meter the app's one meter, made from the :metrics settings, which every
   library counts on and the :metrics settings' :route serves (the app counts its own
   there too: groundwork-background-jobs's add-background-job-to-queue! takes it), :live-repl the
   REPL, started last (groundwork-live-repl's start-repl-server!, the :live-repl settings,
   groundwork's tools in groundwork-live-repl.tools)."
  [{:keys [settings background-job-kinds schedules tables] :as app}]
  (check-settings settings)
  (check-app-functions app)
  (check-app-database app)
  (check-modules-of-pages app)
  (check-tables-leave-gates-and-on-change-out tables)
  (let [running-logger (logs/make-logger (:logs settings))
        log             (logs/log-function running-logger)]
    (when (is-watching? settings) (check-watch-options! app log))
    (let [shaper     (shaped-json/make-shaper-from-settings (:shaped-json settings))
          app-meter (groundwork-metrics/make-meter (dissoc (:metrics settings) :route) {:log log})
          stoppers (atom ())]
      (try
        (let [texts             (languages/load-texts (:languages settings))
              pool              (start-part! stoppers
                                             #(postgres/start-pool! (:postgres settings) {:log log :meter app-meter})
                                             postgres/stop-pool!)
              counting-pool     (start-part! stoppers
                                             #(postgres/start-pool! (assoc (:postgres settings) :pool-size (get-in settings [:daily-caps :pool-size]))
                                                                    {:log   log
                                                                     :meter (groundwork-metrics/make-meter {:jvm-metrics            false
                                                                                               :most-series-per-metric (get-in settings [:metrics :most-series-per-metric])}
                                                                                              {:log log})})
                                             postgres/stop-pool!)
              _                 (if (:migrate-at-start? settings)
                                  @(:slow-part (migrate! pool app log))
                                  (refuse-unsafe-database! pool app))
              running-file-storage     (start-part! stoppers
                                             #(file-storage/make-file-storage (get-in settings [:file-storage :storage]) {:log log :meter app-meter})
                                             file-storage/stop-file-storage!)
              running-mailer   (email/make-mailer (get-in settings [:email :sending])
                                                      {:log log :meter app-meter :shaper shaper :system-actor email-system-actor})
              find-session-step (access/make-find-session-step pool (:access settings))
              handlers          (make-all-ready-handlers pool counting-pool app find-session-step)
              _                 (check-texts-of-reasons! texts handlers)
              answer-as         (answer-as/make-answer-as {:handlers               handlers
                                                           :shaper                   shaper
                                                           :log                    log
                                                           :meter                  app-meter
                                                           :metric-buckets         (get-in settings [:bridge :metric-buckets])
                                                           :find-rate-limit-key    (make-rate-limit-key-finder settings)
                                                           :buckets-before-cleanup (get-in settings [:bridge :rate-limit-buckets-before-cleanup])})
              page-handler      (page/make-page-handler {:shaper              shaper
                                                         :settings          settings
                                                         :browser-settings  (page/make-browser-settings settings (:pages app) (:browser-modules app))
                                                         :pages             (:pages app)
                                                         :render-page       (:render-page app)
                                                         :pool              pool
                                                         :log               log
                                                         :scope-kinds       (:scope-kinds app)
                                                         :find-session-step find-session-step})
              running-live-updates  (start-part! stoppers
                                             #(live-updates/start-listening! pool (get-in settings [:live-updates :listening])
                                                                         {:log           log
                                                                          :meter         app-meter
                                                                          :tables        (make-ready-tables app)
                                                                          ;; groundwork-live-updates tells bridge's sse lines, by bridge's own send
                                                                          :push!         (fn [line message] (bridge/send-message-on-sse-line! line message nil))
                                                                          :find-line-key :groundwork-bridge/channel})
                                             live-updates/stop-listening!)
              workers           (start-part! stoppers
                                             #(background-jobs/start-workers! pool (assoc (:background-jobs settings)
                                                                                   :shaper shaper
                                                                                   :log log
                                                                                   :meter app-meter
                                                                                   :run-in-transaction-as run-as-system-actor!
                                                                                   :given-to-every-run (:given-to-every-background-job-run app)
                                                                                   :kinds (conj (vec background-job-kinds)
                                                                                                (run-once/make-forget-keys-kind (:run-once settings))
                                                                                                (daily-caps/make-forget-counts-kind (:daily-caps settings))
                                                                                                (email/make-background-job-kind running-mailer))
                                                                                   :schedules (conj (vec schedules)
                                                                                                    (run-once/make-forget-keys-schedule (:run-once settings))
                                                                                                    (daily-caps/make-forget-counts-schedule (:daily-caps settings)))))
                                             background-jobs/stop-workers!)
              watching          (when (is-watching? settings)
                                  (start-part! stoppers #(start-watching-browser-build! app log) stop-watching-browser-build!))
              running-health    (health/make-health)
              _                 (metrics/declare-groundwork-metrics! app-meter running-logger (:stage running-health) (:version settings))
              server            (start-part! stoppers
                                             #(bridge/start-server!
                                               (page/wrap-languages
                                                (bridge/make-ring-handler-from-routes
                                                 (make-routes app {:shaper shaper :running-logger running-logger :log log :app-meter app-meter
                                                                   :pool pool :running-health running-health :handlers handlers
                                                                   :running-live-updates running-live-updates :running-file-storage running-file-storage
                                                                   :running-mailer running-mailer :find-session-step find-session-step
                                                                   :answer-as answer-as :page-handler page-handler})
                                                 {:files     (get-in settings [:page :files])
                                                  :not-found page-handler})
                                                (:languages settings)
                                                texts)
                                               (:bridge settings)
                                               {:log log})
                                             bridge/stop-server!)]
          (health/mark-stage! running-health :started)
          (log :info :groundwork/started {:groundwork/version (:version settings) :groundwork/port (:port server)})
          (let [repl (live-repl/start-repl-server! (:live-repl settings)
                                               {:log log :tools (make-live-repl-tools app shaper log app-meter pool counting-pool)})]
            {:settings settings :shaper shaper :logger running-logger :log log :meter app-meter :texts texts :pool pool
             :counting-pool counting-pool
             :file-storage running-file-storage :mailer running-mailer :handlers handlers :live-updates running-live-updates :workers workers :watching watching :health running-health
             :server server :live-repl repl}))
        (catch Throwable e
          (doseq [stop @stoppers] (stop))
          (throw e))))))

(defn stop-app!
  "Closes the REPL first, then answers ready with 503 for the :health
   settings' :not-ready-before-stop-ms, then stops taking requests, stops
   the watcher, lets running background jobs finish, stops listening,
   stops the file storage's client, closes the counting pool and the pool."
  [{:keys [settings log pool counting-pool server workers watching] running-live-updates :live-updates running-health :health repl :live-repl
    running-file-storage :file-storage}]
  (live-repl/stop-repl-server! repl)
  (log :info :groundwork/stopping {:groundwork/version (:version settings)})
  (health/mark-stage! running-health :stopping)
  (Thread/sleep (long (get-in settings [:health :not-ready-before-stop-ms])))
  (bridge/stop-server! server)
  (when watching (stop-watching-browser-build! watching))
  (background-jobs/stop-workers! workers)
  (live-updates/stop-listening! running-live-updates)
  (file-storage/stop-file-storage! running-file-storage)
  (postgres/stop-pool! counting-pool)
  (postgres/stop-pool! pool))

(defn make-leak-test-answerer
  "groundwork-access's leak test's :answer-message for a running app (what
   start-app! returned): (fn [handlers request message]), each message
   answered by groundwork-bridge's answer-message as over http, by the
   app's shaper and log and the :bridge settings' :metric-buckets, with
   :no-rate-limits (the test asks many times). Its messages are counted on
   a meter of their own, never served (:jvm-metrics false), one per table
   of handlers it is handed: the leak test asks with whichever handlers it
   checks, and the app's meter has bridge's metrics declared for the app's
   whole table. groundwork-access runs no message itself; groundwork plugs
   groundwork-bridge's runner in.

     (is (= [] (access/find-leaks (:handlers running) anna-request {:orders [order-in-another-shop]}
                                   {:answer-message (main/make-leak-test-answerer running)
                                    :scope-kinds scope-kinds :seed 7 :size 30})))"
  [{:keys [settings shaper log]}]
  (let [meters (atom {})
        meter-of (fn [handlers]
                   (get (swap! meters #(if (contains? % (set (keys handlers)))
                                         %
                                         (assoc % (set (keys handlers))
                                                (groundwork-metrics/make-meter {:jvm-metrics            false
                                                                                :most-series-per-metric (get-in settings [:metrics :most-series-per-metric])}
                                                                               {:log log}))))
                        (set (keys handlers))))]
    (fn [handlers request message]
      (bridge/answer-message handlers :no-rate-limits request message
                             {:shaper shaper :log log :meter (meter-of handlers) :metric-buckets (get-in settings [:bridge :metric-buckets])}))))
