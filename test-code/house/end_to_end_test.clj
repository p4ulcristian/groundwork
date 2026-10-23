(ns house.end-to-end-test
  "house from end to end: a sample app (house-test.sample.*: shops and
   their orders, Anna in one shop and Bob in another, one page \"orders
   of my shop\" with a write \"add order\", English and German, one module
   loaded on demand), its browser half release-built by showroom, started
   by house's own start-app! on a throwaway Postgres, served over HTTP and
   driven by a headless browser over the DevTools protocol
   (house-test.cdp). No window opens.

   Runs when HOUSE_SQL_TEST_PORT (and _USER, a superuser) and
   HOUSE_E2E_BROWSER (a headless shell or Chrome) are set:

     HOUSE_SQL_TEST_PORT=5499 HOUSE_SQL_TEST_USER=$USER \\
     HOUSE_E2E_BROWSER=~/.cache/ms-playwright/chromium_headless_shell-1248/chrome-linux/headless_shell \\
     clojure -X:test :nses '[house.end-to-end-test]'"
  (:require
   [cellar.api :as database]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.api :as doorman]
   [house-test.cdp :as cdp]
   [house-test.sample.server :as sample]
   [house.server.main.api :as main]
   [jsonista.core :as json]
   [logbook.api :as logbook]
   [meter.api :as meter]
   [safe.api :as safe]
   [showroom.api :as showroom])
  (:import
   (java.net ServerSocket URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util Base64)))

(def ^:private postgres-port (some-> (System/getenv "HOUSE_SQL_TEST_PORT") parse-long))
(def ^:private owner (System/getenv "HOUSE_SQL_TEST_USER"))
(def ^:private browser-binary (System/getenv "HOUSE_E2E_BROWSER"))

(def ^:private anna #uuid "0a000000-0000-4000-8000-00000000000a")
(def ^:private bob #uuid "0b000000-0000-4000-8000-00000000000b")
(def ^:private annas-shop #uuid "5a000000-0000-4000-8000-00000000005a")
(def ^:private bobs-shop #uuid "5b000000-0000-4000-8000-00000000005b")

(def ^:private lines
  "The lines the app's logbook wrote, as JSON data."
  (atom []))

(defn- find-free-port []
  (with-open [socket (ServerSocket. 0)] (.getLocalPort socket)))

(defn- make-temporary-folder [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- make-database-settings [user]
  {:host "localhost" :port postgres-port :database "postgres" :user user :password nil
   :tls :off :tls-root-cert nil :pool-size 8
   :connection-wait-ms 30000 :connection-check-wait-ms 5000
   :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
   :leak-warning-after-ms :none
   :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
   :statement-timeout-ms 5000 :lock-timeout-ms :none :idle-in-transaction-timeout-ms :none
   :time-zone "UTC"
   :metric-buckets {:query-seconds [0.001 0.01 0.1 1] :connection-acquire-seconds [0.001 0.01 0.1 1]
                    :connection-usage-seconds [0.001 0.01 0.1 1]}})

(defn- make-settings [user server-port files-folder]
  {:version           "e2e-1"
   :wire              {:most-shapes-kept 1000
                       :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                                          :max-name-length 50000 :max-document-length nil :max-token-count nil
                                          :max-writing-nesting-depth 1000}}
   :browser-wire      {:most-shapes-kept 1000 :most-keys-kept 10000}
   :logbook           {:lowest-level :info :format :json :errors-kept 100
                       :write-to     #(swap! lines conj (json/read-value %))}
   :meter             {:jvm-metrics true :most-series-per-metric 5000}
   :metrics           {:path "/metrics" :token (safe/make-secret :meter/token "the-metrics-token")}
   :study             {:mode :off :emergency-switch nil}
   :errors            {:path "/house/errors" :token (safe/make-secret :errors/token "the-errors-token") :browser-rate-limit {:max 10 :per-ms 60000}}
   :migrate-at-start? false
   :browser-build     :built
   :server            {:ip "127.0.0.1" :port server-port :proxy-protocol :disable :client-address-header nil :server-header nil
                       :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
                       :addresses {:http "/house/http" :sse "/house/sse" :ws "/house/ws"}
                       :ping-every-ms 1000 :sse-reconnect-after-ms 1000 :ws-reopen-after-ms 1000
                       :stop-wait-ms 2000 :stop-wait-after-cut-ms 500
                       :rate-limit-buckets-before-cleanup 10000
                       :metric-buckets {:answer-seconds [0.005 0.05 0.5 5]}}
   :database          (make-database-settings user)
   :migration         {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}
   :sessions          {:cookie-name "e2e_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                       :allowed-origin (str "http://127.0.0.1:" server-port) :secure-cookie? false}
   :visitors          {:path "/house/visitor"}
   :my-actions        {:rate-limit nil}
   :run-once          {:keep-keys-ms 600000
                       :forget-keys  {:cron "0 * * * *" :timeout-ms 10000 :attempts 1}
                       :retry-writes {:times 2 :wait-ms 300}}
   :background-jobs   {:workers 1 :poll-ms 1000
                       :retry-first-wait-ms 30000 :retry-wait-factor 2 :retry-max-wait-ms 3600000
                       :stuck-after-time-limit-ms 60000 :forget-done-after-ms 604800000
                       :forget-done-every-ms 3600000 :stop-wait-ms 2000
                       :metric-buckets {:job-run-seconds [0.1 1 10]}}
   :attic             {:storage {:endpoint "http://127.0.0.1:1" :bucket "sample" :access-key (safe/make-secret :attic/access-key "key")
                                 :secret-key (safe/make-secret :attic/secret-key "secret")
                                 :region "us-east-1" :public-url nil :link-expiry-seconds 300
                                 :connect-timeout-ms 1000 :request-timeout-ms 1000
                                 :retries {:max-retries 0 :first-wait-ms 0 :max-wait-ms 0}}
                       :upload  {:path "/house/files" :key-prefix "sample" :allowed-content-types #{"image/png"}
                                 :max-file-bytes 1000000 :max-files 1}}
   :postbox           {:sending  {:provider {:name :log :level :info} :tags #{:sample/none}
                                  :background-job {:timeout-ms 10000 :attempts 1}}
                       :webhooks {:delivery-path "/postbox/delivery" :inbound-path "/postbox/inbound"
                                  :token (safe/make-secret :postbox/webhook-token "the-webhook-token-0123") :inbound-domain "in.sample.test"}}
   :doorbell          {:store     {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
                       :listening {:batch-window-ms 50 :check-stopped-every-ms 500
                                   :reconnect-after-ms 1000 :stop-wait-ms 2000}}
   :languages         {:languages ["en" "de"] :default "en" :cookie-name nil
                       :preview-languages [] :fallback-language "en" :catalogs-path "house_test/sample/i18n"}
   :page              {:title "Sample" :viewport "width=device-width, initial-scale=1"
                       :content-security-policy :none :referrer-policy "same-origin" :frame-options "DENY"
                       :files {:folder files-folder :hashed-name-min-hex-digits 8 :hashed-file-cache-seconds 31536000}}
   :health            {:live-path "/health/live" :ready-path "/health/ready"
                       :database-timeout-ms 1000 :not-ready-before-stop-ms 2000}})

(defn- build-browser-half!
  "The sample's browser half, release-built by showroom into a new folder,
   as the deploy builds it; returns the folder."
  []
  (let [folder (make-temporary-folder "house-e2e-files")]
    (with-out-str (showroom/build-for-release! {:entry              'house-test.sample.browser/start!
                                                :modules            sample/browser-modules
                                                :files-folder       folder
                                                :cache-folder       ".shadow-cljs"
                                                :compile-threads    4
                                                :output-feature-set :es2020
                                                :source-maps?       false
                                                :gzip-level         1}))
    folder))

(defn- start-owner-pool! []
  (let [log (logbook/log-function (logbook/make-logbook {:lowest-level :error :format :json :errors-kept 10
                                                         :write-to     (fn [_])}))]
    (database/start-pool! (make-database-settings owner)
                          {:log log :meter (meter/make-meter {:jvm-metrics false :most-series-per-metric 10000} {:log log})})))

(defn- run-sql! [pool text]
  (database/run-statement! pool (database/make-query-from-sql-text "e2e setup" text) {}))

(defn- count-orders-with-note [pool note]
  (:n (database/find-one-row pool (database/make-query-from-sql-text "e2e: orders with a note"
                                                                     "SELECT count(*) AS n FROM orders WHERE order__note = :order/note")
                             {:order/note note})))

(defn- make-database-afresh!
  "As the owner: an empty database, the role the app runs as, the
   migration, two shops, Anna founding one and Bob the other, and one
   order in each."
  [owner-pool app]
  (run-sql! owner-pool (str "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE; "
                            "DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS house CASCADE; "
                            "DROP SCHEMA IF EXISTS attic CASCADE; DROP SCHEMA IF EXISTS postbox CASCADE"))
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'house_e2e_app') THEN CREATE ROLE house_e2e_app LOGIN; END IF; END $$")
  (with-out-str (main/migrate-app! app (make-database-settings owner) "house_e2e_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" annas-shop "', 'Anna''s'), ('" bobs-shop "', 'Bob''s')"))
  (database/run-in-transaction! owner-pool {:read-only? false}
                                (fn [transaction]
                                  (doorman/add-scope! transaction sample/scope-kinds :shop annas-shop anna nil)
                                  (doorman/add-scope! transaction sample/scope-kinds :shop bobs-shop bob nil)))
  (run-sql! owner-pool (str "INSERT INTO orders (order__shop_id, order__note) VALUES ('" annas-shop "', 'a-seed'), ('" bobs-shop "', 'b-seed')")))

(defn- get-text
  "A plain GET: [status body], with a bearer token when given."
  [url token]
  (let [response (.send (HttpClient/newHttpClient)
                        (cond-> (HttpRequest/newBuilder (URI. url))
                          token (.header "authorization" (str "Bearer " token))
                          true  (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(defn- read-metric
  "A sample's value in the metrics text, or nil."
  [text sample-name]
  (some->> (str/split-lines text)
           (some #(when (str/starts-with? % (str sample-name " ")) %))
           (re-find #" (\S+)$")
           second
           parse-double))

(defn- list-notes [connection page]
  (cdp/evaluate connection page "Array.from(document.querySelectorAll('#orders li')).map(li => li.textContent)"))

(defn- wait-for-notes
  "The page's notes once they are these, else what they are after timeout-ms."
  [connection page notes timeout-ms]
  (or (cdp/wait-for connection page (str "(() => { const n = Array.from(document.querySelectorAll('#orders li')).map(li => li.textContent);"
                                         " return JSON.stringify(n) === " (json/write-value-as-string (json/write-value-as-string notes))
                                         " ? n : null })()")
                    timeout-ms)
      (list-notes connection page)))

(defn- find-header [headers header-name]
  (some (fn [[k v]] (when (= header-name (str/lower-case (name k))) v)) headers))

(defn- has-program? [program-name]
  (some #(.canExecute (io/file % program-name)) (str/split (or (System/getenv "PATH") "") #":")))

(deftest the-sample-apps-development-settings-load
  (if-not (has-program? "sops")
    (println "house.end-to-end-test: the development settings skipped, sops is not on the PATH")
    (let [settings (safe/load {:file  "test-code/house_test/sample/settings/development.edn"
                               :shape main/settings-shape
                               :env   {"PATH"                 (System/getenv "PATH")
                                       "SAMPLE_DATABASE_PORT" "5432"
                                       "SAMPLE_DATABASE_USER" "sample"}})]
      (is (= settings (main/check-settings settings)) "house's own check takes them as they are")
      (is (= 5432 (get-in settings [:database :port])) "#env filled, as its type")
      (is (= "#secret :errors/token" (pr-str (get-in settings [:errors :token]))) "a secret prints as its name")
      (is (= "development-only-errors-token" (safe/reveal (get-in settings [:errors :token])))
          "read from the development sops file, with the development-only key")
      (is (not (str/includes? (pr-str settings) "development-only-")) "no secret's text in the settings printed"))))

(deftest the-sample-app-in-a-headless-browser
  (if-not (and postgres-port browser-binary)
    (println "house.end-to-end-test: skipped, HOUSE_SQL_TEST_PORT and HOUSE_E2E_BROWSER are not both set")
    (let [files-folder (build-browser-half!)
          server-port  (find-free-port)
          origin       (str "http://127.0.0.1:" server-port)
          app          (sample/make-app (make-settings "house_e2e_app" server-port files-folder))
          owner-pool   (start-owner-pool!)]
      (try
        (make-database-afresh! owner-pool app)
        (let [running    (main/start-app! app)
              stopped?   (atom false)
              connection (cdp/launch! browser-binary)
              sign-in    #(:set-cookie (doorman/sign-in! (:pool running) {:headers {}} %
                                                         {:settings (:sessions (:settings app)) :tables (:tables app) :session-flags #{}}))
              annas      (cdp/make-context! connection)
              bobs       (cdp/make-context! connection)
              dialogs    (atom [])
              failed-add (atom nil)
              adds-seen  (atom [])
              metrics    #(second (get-text (str origin "/metrics") "the-metrics-token"))]
          (cdp/set-cookie! connection annas "127.0.0.1" (sign-in anna))
          (cdp/set-cookie! connection bobs "127.0.0.1" (sign-in bob))
          ;; a dialog is answered "no" (the unsaved guard's confirm), on the hook's own thread
          (cdp/add-hook! connection (fn [{:keys [method sessionId params]}]
                                      (when (= "Page.javascriptDialogOpening" method)
                                        (swap! dialogs conj (:message params))
                                        (cdp/send! connection sessionId "Page.handleJavaScriptDialog" {:accept false}))))
          ;; while Fetch is on: the first "add order" answer is lost on its way (the server saved it), the rest pass
          (cdp/add-hook! connection (fn [{:keys [method sessionId params]}]
                                      (when (= "Fetch.requestPaused" method)
                                        (let [{:keys [requestId request]} params
                                              add? (str/includes? (str (:postData request)) "orders/add")]
                                          (when add? (swap! adds-seen conj (find-header (:headers request) "idempotency-key")))
                                          (if (and add? (compare-and-set! failed-add nil :failing))
                                            (let [{:keys [body base64Encoded]} (cdp/send! connection sessionId "Fetch.getResponseBody" {:requestId requestId})]
                                              (reset! failed-add (if base64Encoded (String. (.decode (Base64/getDecoder) ^String body) "UTF-8") body))
                                              (cdp/send! connection sessionId "Fetch.failRequest" {:requestId requestId :errorReason "ConnectionReset"}))
                                            (cdp/send! connection sessionId "Fetch.continueRequest" {:requestId requestId}))))))
          (try
            (let [anna-1 (cdp/open-page! connection annas)
                  anna-2 (cdp/open-page! connection annas)
                  bob-1  (cdp/open-page! connection bobs)]
              (testing "1. the page loads in its language; /de/… is German, from dictionary's catalog"
                (cdp/go-to! connection anna-1 (str origin "/shops/" annas-shop "/orders"))
                (is (= "Orders of my shop" (cdp/wait-for connection anna-1 "document.querySelector('#title')?.textContent || null" 15000)))
                (is (= "en" (cdp/evaluate connection anna-1 "document.documentElement.lang")))
                (cdp/go-to! connection anna-2 (str origin "/de/shops/" annas-shop "/orders"))
                (is (= "Bestellungen meines Ladens"
                       (cdp/wait-for connection anna-2 "document.querySelector('#title')?.textContent || null" 15000)))
                (is (= "de" (cdp/evaluate connection anna-2 "document.documentElement.lang")))
                (is (= "Bestellung hinzufügen" (cdp/wait-for connection anna-2 "document.querySelector('#add')?.textContent || null" 5000))))
              (testing "2. the sse line opens; Anna sees only her shop's orders (the gates)"
                (is (some #(and (str/ends-with? (get-in % [:params :response :url]) "/house/sse")
                                (= 200 (get-in % [:params :response :status]))
                                (= "text/event-stream" (get-in % [:params :response :mimeType])))
                          (cdp/list-events connection (:session-id anna-1) "Network.responseReceived"))
                    "the browser's sse line, answered as an event stream")
                (is (= ["a-seed"] (wait-for-notes connection anna-1 ["a-seed"] 5000)) "shop B's b-seed never reaches her"))
              (testing "3. Bob adds in his shop: his screen changes, Anna's doesn't; Anna adds in hers: her other open screen updates, not reloaded"
                (cdp/go-to! connection bob-1 (str origin "/shops/" bobs-shop "/orders"))
                (is (= ["b-seed"] (wait-for-notes connection bob-1 ["b-seed"] 10000)))
                (cdp/evaluate connection anna-1 "window.sampleNotReloaded = true")
                (cdp/type-text! connection bob-1 "#note" "b-new")
                (cdp/click! connection bob-1 "#add")
                (is (= ["b-seed" "b-new"] (wait-for-notes connection bob-1 ["b-seed" "b-new"] 5000)))
                (Thread/sleep 1500)
                (is (= ["a-seed"] (list-notes connection anna-1)) "Bob's order never shows on Anna's screen")
                (cdp/type-text! connection anna-2 "#note" "a-new")
                (cdp/click! connection anna-2 "#add")
                (is (= ["a-seed" "a-new"] (wait-for-notes connection anna-1 ["a-seed" "a-new"] 5000))
                    "the other tab hears the ring and asks again")
                (is (true? (cdp/evaluate connection anna-1 "window.sampleNotReloaded === true")) "without a reload"))
              (testing "4. can-i? shows the add button to Anna in her shop only"
                (is (true? (cdp/evaluate connection anna-1 "document.querySelector('#add') !== null")))
                (cdp/go-to! connection anna-2 (str origin "/shops/" bobs-shop "/orders"))
                (is (= "ready" (cdp/wait-for connection anna-2 "document.querySelector('main')?.dataset.actions === 'ready' && document.querySelector('#orders') ? 'ready' : null" 10000)))
                (is (= [] (list-notes connection anna-2)) "Bob's shop's orders: none for Anna")
                (is (false? (cdp/evaluate connection anna-2 "document.querySelector('#add') !== null")) "and no add button"))
              (testing "5. a write whose answer is lost is sent again with the same one-time key: one row, the same answer"
                (cdp/send! connection (:session-id anna-1) "Fetch.enable" {:patterns [{:urlPattern "*/house/http" :requestStage "Response"}]})
                (cdp/type-text! connection anna-1 "#note" "a-retry")
                (cdp/click! connection anna-1 "#add")
                (let [shown (cdp/wait-for connection anna-1 "(t => t.startsWith('ready ') ? t : null)(document.querySelector('#last-add').textContent)" 10000)]
                  (cdp/send! connection (:session-id anna-1) "Fetch.disable" {})
                  (is (= 2 (count @adds-seen)) "sent twice")
                  (is (and (some? (first @adds-seen)) (apply = @adds-seen)) "with the same key")
                  (is (= 1 (count-orders-with-note owner-pool "a-retry")) "saved once")
                  (is (= (str "ready " (get-in (json/read-value @failed-add) ["orders/add" "order/id"])) shown)
                      "the answer shown is the one that was lost: the server kept it for the key")))
              (testing "6. an error thrown in the browser reaches the errors route"
                (cdp/evaluate connection anna-1 "setTimeout(() => { throw new Error('e2e boom') }, 0), true")
                (is (= 401 (first (get-text (str origin "/house/errors?since=0") nil))))
                (let [kept (loop [tries 50]
                             (let [[_ body] (get-text (str origin "/house/errors?since=0") "the-errors-token")
                                   found    (some #(when (= "browser/uncaught" (get % "event")) %) (json/read-value body))]
                               (if (or found (zero? tries)) found (do (Thread/sleep 100) (recur (dec tries))))))]
                  (is (str/includes? (str (get-in kept ["data" "browser/message"])) "e2e boom"))
                  (is (= (str anna) (get-in kept ["context" "person-id"])) "with who was asking")))
              (testing "7. /metrics, only with its token: mailroom's messages, cellar's query times, logbook's lines"
                (is (= 401 (first (get-text (str origin "/metrics") nil))))
                (let [text (metrics)]
                  (is (pos? (or (read-metric text "mailroom_messages_total{name=\"orders/of-shop\",outcome=\"ok\",pipe=\"http\"}") 0)))
                  (is (pos? (or (read-metric text "mailroom_messages_total{name=\"orders/add\",outcome=\"ok\",pipe=\"http\"}") 0)))
                  (is (pos? (or (read-metric text "cellar_query_seconds_count{query=\"sample: the orders of a shop\"}") 0)))
                  (is (pos? (or (read-metric text "logbook_lines_total{level=\"info\"}") 0)))
                  (is (pos? (or (read-metric text "logbook_lines_total{level=\"error\"}") 0)) "the browser's error among them")))
              (testing "9. a module loaded on demand: its stylesheet in before its code"
                (cdp/click! connection anna-1 "#to-reports")
                (is (= "Reports" (cdp/wait-for connection anna-1 "document.querySelector('#reports h1')?.textContent || null" 10000)))
                (let [sent     (cdp/list-events connection (:session-id anna-1) "Network.requestWillBeSent")
                      finished (cdp/list-events connection (:session-id anna-1) "Network.loadingFinished")
                      find-one #(some (fn [event] (when (re-find % (get-in event [:params :request :url])) (:params event))) sent)
                      css      (find-one #"/css/reports\.[0-9A-F]+\.css$")
                      js       (find-one #"/js/reports\.[0-9A-F]+\.js$")
                      css-done (some #(when (= (:requestId css) (get-in % [:params :requestId])) (:params %)) finished)]
                  (is (and css js) "both asked for")
                  (is (< (:timestamp css-done) (:timestamp js)) "the stylesheet had loaded when the code was asked for")
                  (is (= "rgb(1, 2, 3)" (cdp/evaluate connection anna-1 "window.sampleReportsColourAtLoad"))
                      "its rules applied when its code first ran"))
                (cdp/evaluate connection anna-1 "history.back(), true")
                (is (cdp/wait-for connection anna-1 "document.querySelector('#orders') !== null" 5000)))
              (testing "10. the unsaved guard holds a navigation"
                (cdp/type-text! connection anna-1 "#note" "not saved")
                (cdp/click! connection anna-1 "#to-reports")
                (Thread/sleep 500)
                (is (= ["There is an unsaved note. Leave anyway?"] @dialogs) "the person is asked")
                (is (= (str "/shops/" annas-shop "/orders") (cdp/evaluate connection anna-1 "location.pathname"))
                    "and, saying no, stays")
                (is (true? (cdp/evaluate connection anna-1 "document.querySelector('#reports') === null && document.querySelector('#orders') !== null"))))
              (testing "11. a dropped sse line is closed by the next ping"
                (let [open-lines #(read-metric (metrics) "mailroom_open_lines{pipe=\"sse\"}")
                      before     (open-lines)
                      bob-2      (cdp/open-page! connection bobs)]
                  (cdp/go-to! connection bob-2 (str origin "/shops/" bobs-shop "/orders"))
                  (is (= ["b-seed" "b-new"] (wait-for-notes connection bob-2 ["b-seed" "b-new"] 10000)))
                  (is (= (inc before) (loop [tries 50] (let [n (open-lines)] (if (or (= n (inc before)) (zero? tries)) n (do (Thread/sleep 100) (recur (dec tries))))))))
                  (cdp/close-page! connection bob-2)
                  (let [started (System/currentTimeMillis)
                        after   (loop [tries 60] (let [n (open-lines)] (if (or (= n before) (zero? tries)) n (do (Thread/sleep 100) (recur (dec tries))))))]
                    (is (= before after) "the gauge back down")
                    (is (< (- (System/currentTimeMillis) started) 3000) "within a ping or two (:ping-every-ms 1000)"))))
              (testing "8. ready 200; 503 while stopping"
                (is (= 200 (first (get-text (str origin "/health/ready") nil))))
                (let [stopping (future (main/stop-app! running))]
                  (reset! stopped? true)
                  (Thread/sleep 500)
                  (is (= [503 "{\"status\":\"not-ready\",\"version\":\"e2e-1\",\"reason\":\"the app is stopping\"}"]
                         (get-text (str origin "/health/ready") nil)))
                  @stopping)))
            (finally
              (cdp/quit! connection)
              (when-not @stopped? (main/stop-app! running)))))
        (finally
          (database/stop-pool! owner-pool)
          (doseq [file (reverse (file-seq (io/file files-folder)))]
            (io/delete-file file true)))))))
