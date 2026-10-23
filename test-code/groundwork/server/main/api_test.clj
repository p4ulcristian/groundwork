(ns groundwork.server.main.api-test
  "The whole server, started as production starts it: migrate-app! as the
   owner, then start-app! as a role that owns nothing. The page shell, a
   message with its notes, and doorbell: a new order is told to every open
   sse line as only its table, and each person's asking again sees, through the
   gates, only their own shop's orders. One message's lines, written by
   bridge, by cellar through doorbell and by the app's own step, carry
   its request id and the person doorman found; the errors route hands a
   monitor the kept errors, only with its token; a browser's error, sent
   as a message, is kept with them. Then the health checks: live and
   ready, a database that doesn't answer or lets nobody in, and stopping;
   the metrics route, a visitor's session, the REPL's tools; an upload
   through doorman's session and the app's :may-upload? (stored in a real
   S3 API when GROUNDWORK_S3_TEST_ENDPOINT is set: SeaweedFS, as attic's tests),
   an email queued in a write's transaction and sent by the workers to the
   log, and its bounce handed to the app's function by its tag.
   Runs when
   GROUNDWORK_SQL_TEST_PORT is set."
  (:require
   [attic.api :as attic]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as database]
   [dictionary.api :as dictionary]
   [doorbell.api :as doorbell]
   [doorman.api :as doorman]
   [groundwork.server.main.api :as main]
   [groundwork.server.main.app-routes :as app-routes]
   [groundwork.server.main.page :as page]
   [groundwork.server.main.run-once :as run-once]
   [jsonista.core :as json]
   [logbook.api :as logbook]
   [bridge.api :as bridge]
   [meter.api :as meter]
   [nrepl.core :as nrepl]
   [postbox.api :as postbox]
   [safe.api :as safe]
   [study.api :as study]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as aws-credentials])
  (:import
   (java.io BufferedReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "GROUNDWORK_SQL_TEST_PORT") parse-long))
(def ^:private s3-endpoint (System/getenv "GROUNDWORK_S3_TEST_ENDPOINT"))
(def ^:private owner (System/getenv "GROUNDWORK_SQL_TEST_USER"))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops :parent nil :single? false
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}
                     :shop-emails/see #{:read} :shop-emails/mark-bounced #{:read :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}
           :denies  []}}))

(def ^:private shops
  {:table    :shops
   :columns  [[:shop/id :uuid {:primary-key? true}]]
   :indexes  []
   :uniques  []
   :checks   []
   :references []
   :triggers  []
   :access   :public
   :doorbell :tell})

(def ^:private orders
  {:table    :orders
   :columns  [[:order/id :uuid {:primary-key? true :default :random-uuid}]
              [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes  []
   :uniques  []
   :checks   []
   :references []
   :triggers  []
   :access   [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}]
   :doorbell :tell})

(def ^:private shop-emails
  "A shop's address, which a bounce marks: postbox's webhook function, run
   as doorman's system actor :postbox, may, and nothing else of the shop's."
  {:table    :shop-emails
   :columns  [[:shop-email/address :string {:primary-key? true}]
              [:shop-email/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
              [:shop-email/bounced :boolean {:default false}]]
   :indexes  [{:columns [[:shop-email/shop-id :ascending :nulls-last]]
               :method :btree :ops {} :unique? false :where nil}]
   :uniques  []
   :checks   []
   :references []
   :triggers  []
   :access   [{:allow :shop-emails/see :only :all-of-the-action :to [:member :shop-email/shop-id]}
              {:allow :shop-emails/mark-bounced :only :all-of-the-action :to [:system :postbox]}]
   :doorbell :tell})

(def ^:private mark-bounced
  (database/make-query-from-sql-text "a bounce" "UPDATE shop_emails SET shop_email__bounced = true WHERE shop_email__address = :address"))

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")
(def ^:private order-71 #uuid "71000000-0000-4000-8000-000000000071")
(def ^:private order-91 #uuid "91000000-0000-4000-8000-000000000091")

(def ^:private find-all-orders
  (database/make-query-from-sql-text "all orders, no WHERE" "SELECT order__id FROM orders ORDER BY order__id"))

(def ^:private insert-order
  (database/make-query-from-sql-text "a new order" "INSERT INTO orders (order__shop_id) VALUES (:order/shop-id) RETURNING order__id"))

(def ^:private count-kept-keys
  (database/make-query-from-sql-text "one-time keys kept" "SELECT count(*) AS n FROM groundwork.run_once_keys WHERE run_once_key__key = :run-once-key/key"))

(def ^:private find-untyped
  (database/make-query-from-sql-text "a param Postgres can't type" "SELECT :x IS NULL AS b"))

(def ^:private lines
  "The lines the app's logbook wrote, as JSON data."
  (atom []))

(defn- find-lines [event]
  (filterv #(= event (get % "event")) @lines))

(def ^:private handlers
  {:orders/count {:action :orders/see :scope :every-scope-holding-the-action :params-shape [:map] :answer-shape :int :refusals {} :rate-limit {:max 2 :per-ms 60000}
                  :run-steps-in nil
                  :steps  [{:name :answer-count :enter #(bridge/add-answer-to-context % 0)}]}
   :orders/all {:action :orders/see :scope :every-scope-holding-the-action :params-shape [:map] :answer-shape [:vector :uuid] :refusals {} :rate-limit nil :run-steps-in nil
                :steps  [{:name  :answer-all-orders
                          :enter #(bridge/add-answer-to-context
                                   % (mapv :order/id (database/find-rows (:doorman/transaction %) find-all-orders {})))}]}
   :orders/add {:action :orders/edit :scope [:shop :order/shop-id] :params-shape [:map [:order/shop-id :uuid] [:test/wait-ms :int]] :answer-shape :uuid
                :refusals {} :rate-limit nil :run-steps-in nil
                :steps  [{:name  :add-order
                          :enter (fn [{:bridge/keys [message-data] :as context}]
                                   (Thread/sleep (long (:test/wait-ms message-data)))
                                   (bridge/add-answer-to-context
                                    context (:order/id (database/find-one-row (:doorman/transaction context) insert-order
                                                                              (select-keys message-data [:order/shop-id])))))}]}
   :orders/add-badly {:action :orders/edit :scope [:shop :order/shop-id] :params-shape [:map [:order/shop-id :uuid]] :answer-shape :uuid
                      :refusals {} :rate-limit nil :run-steps-in nil
                      :steps  [{:name  :add-order-and-answer-a-number
                                :enter (fn [{:bridge/keys [message-data] :as context}]
                                         (database/find-one-row (:doorman/transaction context) insert-order message-data)
                                         (bridge/add-answer-to-context context 42))}]}
   :orders/broken {:action :orders/see :scope :every-scope-holding-the-action :params-shape [:map] :answer-shape :int :refusals {} :rate-limit nil :run-steps-in nil
                   :steps  [{:name  :say-and-ask-untyped
                             :enter (fn [{:bridge/keys [log] :as context}]
                                      (log :info :test/about-to-break {})
                                      (database/find-one-row (:doorman/transaction context) find-untyped {:x "a"})
                                      context)}
                            {:name :break :enter (fn [_] (throw (ex-info "the app's step broke" {})))}]}})

(def ^:private files-folder
  "A release build's folder, as the deploy leaves it: a manifest and one hashed file."
  (let [folder (.toFile (java.nio.file.Files/createTempDirectory "groundwork-files" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (io/make-parents (io/file folder "js" "x"))
    (spit (io/file folder "js" "manifest.edn") (pr-str [{:module-id :main :output-name "main.0A1B2C3D4E5F.js"}
                                                        {:module-id :admin :output-name "admin.6F5E4D3C2B1A.js"}]))
    (spit (io/file folder "js" "main.0A1B2C3D4E5F.js") "start();")
    (with-open [out (java.util.zip.GZIPOutputStream. (io/output-stream (io/file folder "js" "main.0A1B2C3D4E5F.js.gz")))]
      (.write out (.getBytes "start();")))
    (io/make-parents (io/file folder "css" "x"))
    (spit (io/file folder "css" "manifest.edn") (pr-str [{:module-id :admin :output-name "admin.1A2B3C4D5E6F.css"}
                                                         {:module-id :main :output-name "main.9F8E7D6C5B4A.css"}]))
    (spit (io/file folder "robots.txt") "User-agent: *")
    (str folder)))

(defn- make-database-settings [user]
  {:host "localhost" :port port :database "postgres" :user user :password nil
   :tls :off :tls-root-cert nil
   :pool-size 4
   :connection-wait-ms 30000 :connection-check-wait-ms 5000
   :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
   :leak-warning-after-ms :none
   :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
   :statement-timeout-ms 5000 :lock-timeout-ms :none :idle-in-transaction-timeout-ms :none
   :time-zone "UTC"
   :metric-buckets {:query-seconds [0.01 0.1 1] :connection-acquire-seconds [0.01 0.1 1]
                    :connection-usage-seconds [0.01 0.1 1]}})

(defn- start-pool!
  "A pool as a deploy's script starts one: its own log and meter."
  [user]
  (let [log (logbook/log-function (logbook/make-logbook {:lowest-level :error :format :json :errors-kept 10
                                                         :write-to     (fn [_])}))]
    (database/start-pool! (make-database-settings user)
                          {:log log :meter (meter/make-meter {:jvm-metrics false :most-series-per-metric 10000} {:log log})})))

(defn- make-settings [user]
  {:version           "test-1"
   :wire              {:most-shapes-kept 1000
                       :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                                          :max-name-length 50000 :max-document-length nil :max-token-count nil
                                          :max-writing-nesting-depth 1000}}
   :browser-wire      {:most-shapes-kept 1000 :most-keys-kept 10000}
   :logbook           {:lowest-level :debug :format :json :errors-kept 100
                       :write-to     #(swap! lines conj (json/read-value %))}
   :meter             {:jvm-metrics true :most-series-per-metric 10000}
   :metrics           {:path "/metrics" :token (safe/make-secret :meter/token "the-metrics-token")}
   :study             {:mode :development :bind-address "127.0.0.1" :port 0 :middleware [] :port-file nil}
   :visitors          {:path "/groundwork/visitor" :rate-limit {:max 5 :per-ms 60000}}
   :errors            {:path "/groundwork/errors" :token (safe/make-secret :errors/token "the-errors-token") :browser-rate-limit {:max 2 :per-ms 60000}}
   :migrate-at-start? false
   :browser-build     :built
   :server            {:ip "127.0.0.1" :port 0 :proxy-protocol :disable :client-address-header nil :server-header nil
                       :max-body-bytes 8388608 :max-request-head-bytes 8192 :max-ws-message-bytes 4194304
                       :idle-timeout-ms 60000 :threads {:min 8 :max 50 :idle-timeout-ms 60000}
                       :addresses {:http "/groundwork/http" :sse "/groundwork/sse" :ws "/groundwork/ws"}
                       :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
                       :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
                       :rate-limit-buckets-before-cleanup 10000
                       :metric-buckets {:answer-seconds [0.01 0.1 1]}}
   :database          (make-database-settings user)
   :migration         {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}
   :sessions          {:cookie-name "groundwork_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                       :allowed-origin "http://app.test" :secure-cookie? false
                       :token-lead "app_" :token-shown-characters 10 :token-longest-days 30}
   :my-actions        {:rate-limit {:max 60 :per-ms 60000}}
   :run-once          {:keep-keys-ms 1500
                       :forget-keys  {:cron "0 * * * *" :timeout-ms 10000 :attempts 1}
                       :retry-writes {:times 1 :wait-ms 1000}}
   :background-jobs   {:workers 1 :poll-ms 1000
                       :retry-first-wait-ms 30000 :retry-wait-factor 2 :retry-max-wait-ms 3600000
                       :stuck-after-time-limit-ms 60000 :forget-done-after-ms 604800000
                       :forget-done-every-ms 3600000 :stop-wait-ms 5000
                       :metric-buckets {:job-run-seconds [0.1 1 10]}}
   :attic             {:storage {:endpoint (or s3-endpoint "http://127.0.0.1:1") :bucket "groundwork-test"
                                 :access-key (safe/make-secret :attic/access-key (or (System/getenv "GROUNDWORK_S3_TEST_ACCESS_KEY") "key"))
                                 :secret-key (safe/make-secret :attic/secret-key (or (System/getenv "GROUNDWORK_S3_TEST_SECRET_KEY") "secret"))
                                 :region "us-east-1" :public-url nil :link-expiry-seconds 300
                                 :connect-timeout-ms 2000 :request-timeout-ms 10000
                                 :retries {:max-retries 1 :first-wait-ms 100 :max-wait-ms 100}}
                       :upload  {:path "/groundwork/files" :key-prefix "orders/photos" :allowed-content-types #{"image/png"}
                                 :max-file-bytes 1000000 :max-files 4}}
   :postbox           {:sending  {:provider {:name :log :level :info} :tags #{:orders/confirmation}
                                  :background-job {:timeout-ms 10000 :attempts 3}}
                       :webhooks {:delivery-path "/postbox/delivery" :inbound-path "/postbox/inbound"
                                  :token (safe/make-secret :postbox/webhook-token "the-webhook-token-0123") :inbound-domain "in.app.test"}}
   :doorbell          {:store     {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
                       :listening {:batch-window-ms 50 :check-stopped-every-ms 500
                                   :reconnect-after-ms 1000 :stop-wait-ms 2000}}
   :languages         {:languages [{:code "en" :name "English"} {:code "de" :name "Deutsch"}] :default "en" :cookie-name "lang"
                       :preview-languages [] :fallback-language "en" :catalogs-path "groundwork_test/i18n"}
   :page              {:viewport "width=device-width, initial-scale=1"
                       :content-security-policy {:policy "script-src 'nonce-{nonce}' 'strict-dynamic'; object-src 'none'" :nonce? true}
                       :referrer-policy "same-origin" :frame-options "DENY"
                       :files {:folder files-folder :hashed-name-min-hex-digits 8 :hashed-file-cache-seconds 31536000}}
   :health            {:live-path "/health/live" :ready-path "/health/ready"
                       :database-timeout-ms 500 :not-ready-before-stop-ms 1000}})

(def ^:private delivery-events
  "The bounces and complaints groundwork handed to the app's function."
  (atom []))

(def ^:private app-routes
  "The app's own routes: a page-like read as the asker, a sign-in, a
   call of another protocol answered as the request's asker (its cookie or
   its token), a webhook that reads its raw body."
  {:app/orders  {:address "/app/orders" :method :get :kind :person :rate-limit nil
                 :handler (fn [_request {:keys [asker run-as-asker!]}]
                            {:status 200 :headers {"content-type" "text/plain"}
                             :body (str (:person-id asker) " "
                                        (run-as-asker! {:read-only? true} #(count (database/find-rows % find-all-orders {}))))})}
   :app/sign-in {:address "/app/sign-in" :method :post :kind :person :rate-limit nil
                 :handler (fn [_request {:keys [sign-in!]}]
                            {:status 200 :headers {"set-cookie" (:set-cookie (sign-in! anna #{}))} :body "signed in"})}
   :app/call    {:address "/app/call" :method :post :kind :person :rate-limit nil
                 :handler (fn [request {:keys [answer-as]}]
                            {:status 200 :headers {"content-type" "application/json"}
                             :body (json/write-value-as-string
                                    (answer-as (update-keys (json/read-value (slurp (:body request))) keyword) request))})}
   :app/hook    {:address "/app/hook" :method :post :kind :provider :rate-limit {:max 2 :per-ms 60000}
                 :handler (fn [request _tools]
                            {:status 200 :headers {"content-type" "text/plain"} :body (str "got " (slurp (:body request)))})}})

(defn- render-page
  "The test app's pages: its title, a description, a script of its own
   with the request's nonce, a body in the request's language saying who
   asked; the not-found page for an address no page has."
  [_request page {:keys [nonce asker]}]
  (if page
    {:status      200
     :title       "Shops <test>"
     :description (str "The " (name (:name page)) " page")
     :head-html   (str "<script nonce=\"" nonce "\">window.appStarted = true;</script>\n")
     :body-html   (str "<main>" (dictionary/tr :orders/title {}) " for " (or (:person-id asker) "nobody") "</main>")}
    {:status 404 :title "Not found" :description nil :head-html "" :body-html "<main>No such page</main>"}))

(defn- make-app [user]
  {:settings (make-settings user) :tables [shops orders shop-emails] :extensions [] :functions [] :views [] :changes [] :scope-kinds scope-kinds
   :routes app-routes
   :render-page render-page
   :may-upload? (fn [{:keys [person-id]}] (= anna person-id))
   :on-delivery-event {:orders/confirmation (fn [event {:keys [transaction]}]
                                              (swap! delivery-events conj
                                                     (assoc event
                                                            :marked (database/run-statement! transaction mark-bounced {:address (:address event)})
                                                            :orders-seen (count (database/find-rows transaction find-all-orders {})))))}
   :on-inbound-email {}
   :handlers handlers :pages {:orders/page {:address "/orders/:order-id" :title "Order"}
                              :admin/page  {:address "/admin" :title "Admin" :module "admin"}}
   :browser-entry 'app.browser.main/start!
   :browser-modules {:admin {:entries '[app.browser.admin] :depends-on #{:main}}}
   :background-job-kinds [] :schedules []})

(defn- run-sql! [pool text] (database/run-statement! pool (database/make-query-from-sql-text "test" text) {}))

(defn- refusal-of
  "A name's refusal as JSON data reads it, without its request id: the
   reason as text, its data."
  [reason data]
  {"bridge/refused" (subs (str reason) 1) "bridge/data" data})

(defn- without-request-ids
  "An answer's JSON data, each name's request id taken out (a new one for
   every message), its notes too."
  [answer]
  (update-vals (dissoc answer "bridge/notes") #(if (map? %) (dissoc % "bridge/request-id") %)))

(defn- is-too-many?
  "Whether a name's JSON answer is bridge's :bridge/too-many, with a wait
   and a request id."
  [answer]
  (and (= "bridge/too-many" (get answer "bridge/refused"))
       (pos-int? (get-in answer ["bridge/data" "retry-after-ms"]))
       (string? (get answer "bridge/request-id"))))

(defn- ask-health
  "A plain GET, no origin, no cookie: [status body cache-control]."
  [server-port path]
  (let [response (.send (HttpClient/newHttpClient)
                        (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port path))))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response) (.orElse (.firstValue (.headers response) "cache-control") nil)]))

(defn- send-request [server-port method path cookie body]
  (let [response (.send (HttpClient/newHttpClient)
                        (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port path)))
                          true   (.header "origin" "http://app.test")
                          cookie (.header "cookie" cookie)
                          true   (-> (.method method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
                                     (.build)))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(defn- send-plain
  "A request with the origin only when given: [status body set-cookie]."
  [server-port method path origin cookie body]
  (let [response (.send (HttpClient/newHttpClient)
                        (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port path)))
                          origin (.header "origin" origin)
                          cookie (.header "cookie" cookie)
                          true   (-> (.method method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
                                     (.build)))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response) (.orElse (.firstValue (.headers response) "set-cookie") nil)]))

(defn- send-plain-with-token
  "A GET with Authorization: Bearer token, no origin, no cookie: [status body]."
  [server-port path token]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port path)))
                            (.header "authorization" (str "Bearer " token))
                            (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(defn- send-write
  "A POST of a write with its one-time key in the idempotency-key header:
   [status the answer's named data, parsed]."
  [server-port cookie once-key body]
  (let [response (.send (HttpClient/newHttpClient)
                        (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/groundwork/http")))
                          true     (.header "origin" "http://app.test")
                          true     (.header "cookie" cookie)
                          once-key (.header "idempotency-key" (str once-key))
                          true     (-> (.POST (HttpRequest$BodyPublishers/ofString body)) (.build)))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (dissoc (json/read-value (.body response)) "bridge/notes")]))

(defn- open-sse [server-port cookie]
  (io/reader (.body (.send (HttpClient/newHttpClient)
                           (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/groundwork/sse")))
                               (.header "cookie" cookie) (.build))
                           (HttpResponse$BodyHandlers/ofInputStream)))))

(defn- read-next-data
  "The next sse data line's JSON text, or :nothing within 3 s."
  [^BufferedReader reader]
  (deref (future (loop []
                   (let [line (.readLine reader)]
                     (cond (nil? line)                     :closed
                           (str/starts-with? line "data: ") (subs line 6)
                           :else                           (recur)))))
         3000 :nothing))

(deftest a-request-is-handled-in-its-language
  (let [languages (dictionary/check-settings {:languages [{:code "en" :name "English"} {:code "de" :name "Deutsch"}]
                                              :default "en" :cookie-name "lang" :preview-languages []
                                              :fallback-language "en" :catalogs-path "groundwork_test/i18n"})
        handler   (page/wrap-languages (fn [request] {:uri (:uri request) :text (dictionary/tr :orders/count {:count 2})})
                                       languages (dictionary/load-texts languages))]
    (is (= {:uri "/orders" :text "2 Bestellungen"} (handler {:uri "/de/orders" :headers {}})) "the prefix's, bound for tr")
    (is (= "2 Bestellungen" (:text (handler {:uri "/orders" :headers {"cookie" "lang=de"}}))) "the cookie's")
    (is (= "2 orders" (:text (handler {:uri "/orders" :headers {}}))) "the default's")))

(deftest the-apps-routes-are-checked
  (let [route {:address "/app/x" :method :post :kind :person :rate-limit nil :handler identity}]
    (is (= {:app/x route} (app-routes/check-routes {:app/x route})))
    (is (= :groundwork.server.main/bad-routes
           (:type (ex-data (try (app-routes/check-routes {:app/x (assoc route :kind :public)}) (catch Exception e e))))))
    (is (= {:shared {["/app/x" :post] [:app/x :app/y]}}
           (:errors (ex-data (try (app-routes/check-routes {:app/x route :app/y route}) (catch Exception e e)))))
        "two routes at one address and method")))

(deftest the-settings-are-checked-all-at-once
  (let [problems (try (main/check-settings (-> (make-settings "x") (dissoc :version) (assoc-in [:server :port] "80")
                                               (update :server dissoc :addresses :ping-every-ms)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (contains? problems :version))
    (is (contains? (:server problems) :port))
    (is (= #{:port :addresses :ping-every-ms} (set (keys (:server problems))))
        "bridge's settings have no defaults: each missing one is named"))
  (let [problems (try (main/check-settings (update (make-settings "x") :health dissoc :ready-path :database-timeout-ms))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= #{:ready-path :database-timeout-ms} (set (keys (:health problems))))
        "the health settings have no defaults either"))
  (let [problems (try (main/check-settings (-> (make-settings "x")
                                               (update-in [:migration :slow-part] dissoc :lock-timeout-ms)
                                               (update-in [:doorbell :listening] dissoc :reconnect-after-ms)
                                               (update :database dissoc :connection-wait-ms)
                                               (update :background-jobs dissoc :stop-wait-ms)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= {:migration       {:slow-part {:lock-timeout-ms ["missing required key"]}}
            :doorbell        {:listening {:reconnect-after-ms ["missing required key"]}}
            :database        {:connection-wait-ms ["missing required key"]}
            :background-jobs {:stop-wait-ms ["missing required key"]}}
           problems)
        "the migration's limits, the listener's, the pool's and the workers' have no defaults"))
  (let [problems (try (main/check-settings (-> (make-settings "x")
                                               (update-in [:wire :json-limits] dissoc :max-string-length)
                                               (dissoc :browser-wire)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= {:wire         {:json-limits {:max-string-length ["missing required key"]}}
            :browser-wire ["missing required key"]}
           problems)
        "wire's settings, the JVM's and the browser's, have no defaults: groundwork makes the one wire from them"))
  (let [problems (try (main/check-settings (-> (make-settings "x")
                                               (update :run-once dissoc :keep-keys-ms)
                                               (update-in [:run-once :forget-keys] dissoc :cron)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= {:run-once {:keep-keys-ms ["missing required key"] :forget-keys {:cron ["missing required key"]}}} problems)
        "how long a one-time key is kept, and when the old ones are forgotten, are written out"))
  (is (thrown? clojure.lang.ExceptionInfo
               (main/check-settings (assoc-in (make-settings "x") [:run-once :retry-writes] {:times 3 :wait-ms 1000})))
      "a write's retries end before its key is forgotten")
  (let [problems (try (main/check-settings (update (make-settings "x") :my-actions dissoc :rate-limit))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= {:my-actions {:rate-limit ["missing required key"]}} problems)
        ":doorman/my-actions's rate limit is written out, nil for none"))
  (let [problems (try (main/check-settings (-> (make-settings "x")
                                               (update :logbook dissoc :lowest-level)
                                               (update :errors dissoc :token :browser-rate-limit)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= {:logbook {:lowest-level ["missing required key"]}
            :errors  {:token ["missing required key"] :browser-rate-limit ["missing required key"]}}
           problems)
        "logbook's settings and the errors' have no defaults"))
  (let [problems (try (main/check-settings (-> (make-settings "x")
                                               (update :meter dissoc :most-series-per-metric)
                                               (update :metrics dissoc :token)
                                               (dissoc :study :visitors)
                                               (update-in [:server :metric-buckets] dissoc :answer-seconds)
                                               (update :sessions dissoc :visitor-session-days)
                                               (update :background-jobs dissoc :forget-done-every-ms)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= {:meter           {:most-series-per-metric ["missing required key"]}
            :metrics         {:token ["missing required key"]}
            :study           ["missing required key"]
            :visitors        ["missing required key"]
            :server          {:metric-buckets {:answer-seconds ["missing required key"]}}
            :sessions        {:visitor-session-days ["missing required key"]}
            :background-jobs {:forget-done-every-ms ["missing required key"]}}
           problems)
        "the meter's, the metrics route's, the REPL's, the visitors', the buckets and the new library settings have no defaults"))
  (let [problems (try (main/check-settings (-> (make-settings "x")
                                               (update-in [:attic :storage] dissoc :region)
                                               (update-in [:attic :upload] dissoc :path)
                                               (update-in [:postbox :sending] dissoc :tags)
                                               (update-in [:postbox :webhooks] dissoc :token)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (= {:attic   {:storage {:region ["missing required key"]} :upload {:path ["missing required key"]}}
            :postbox {:sending {:tags ["missing required key"]} :webhooks {:token ["missing required key"]}}}
           problems)
        "attic's and postbox's settings, and the routes', have no defaults"))
  (let [problems (try (main/check-settings (-> (make-settings "x")
                                               (assoc-in [:errors :token] "the-errors-token")
                                               (assoc-in [:metrics :token] "the-metrics-token")
                                               (assoc-in [:database :password] "the-password")
                                               (assoc-in [:attic :storage :secret-key] "secret")
                                               (assoc-in [:postbox :webhooks :token] "the-webhook-token-0123")))
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))
        refused  "should be a #secret, read from the settings' secrets source"]
    (is (= {:errors   {:token [refused]}
            :metrics  {:token [refused]}
            :database {:password [refused]}
            :attic    {:storage {:secret-key [refused]}}
            :postbox  {:webhooks {:token [refused]}}}
           (:errors problems))
        "a secret written out as text is refused, each named")
    (is (not-any? #(str/includes? (pr-str problems) %) ["the-errors-token" "the-metrics-token" "the-password" "the-webhook-token"])
        "and its text is never said"))
  (is (= {:attic {:upload {:max-file-bytes ["should be below the :server settings' :max-body-bytes, which holds the whole upload"]}}}
         (try (main/check-settings (assoc-in (make-settings "x") [:attic :upload :max-file-bytes] 8388608))
              (catch clojure.lang.ExceptionInfo e (:errors (ex-data e)))))
      "an uploaded file larger than the server takes is refused at the start")
  (is (thrown? clojure.lang.ExceptionInfo
               (main/check-settings (assoc (make-settings "x") :browser-build {:watch {:entry 'app.browser.main/start!}})))
      "the watch options leave out the entry and the folder: they come from the app and :page")
  (is (thrown? clojure.lang.ExceptionInfo
               (main/check-settings (assoc (make-settings "x") :browser-build {:watch {:log (fn [_ _ _])}})))
      "and the log: groundwork passes its own"))

(deftest a-table-that-writes-what-groundwork-puts-is-refused
  (let [{:keys [type problems]} (try (main/make-ready-tables {:tables      [(assoc shops :on-change nil) (assoc orders :gates nil)]
                                                              :scope-kinds scope-kinds})
                                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork.server.main/bad-tables type))
    (is (= [":shops writes its own :on-change, which groundwork puts on it: doorbell's, from its :doorbell"
            ":orders writes its own :gates, which groundwork puts on it: doorman's, from its :access"]
           problems)
        "each named, not overwritten without a word"))
  (is (= [nil {:function "doorbell.tell" :arguments []}]
         ((juxt :gates :on-change) (first (main/make-ready-tables {:tables [shops] :scope-kinds scope-kinds}))))
      "one that leaves them out gets doorman's and doorbell's"))

(deftest the-whole-server
  (if-not port
    (println "groundwork.server.main.api-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [owner-pool (start-pool! owner)]
      (try
        (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE; DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS groundwork CASCADE; DROP SCHEMA IF EXISTS attic CASCADE; DROP SCHEMA IF EXISTS postbox CASCADE")
        (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_app') THEN CREATE ROLE groundwork_app LOGIN; END IF; END $$")
        (testing "an app's handler named :doorman/my-actions is refused: it is groundwork's"
          (is (= :groundwork.server.main/handler-name-taken
                 (try (main/start-app! (-> (make-app owner)
                                           (assoc-in [:settings :migrate-at-start?] true)
                                           (assoc-in [:handlers :doorman/my-actions] (:orders/count handlers))))
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE; DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS groundwork CASCADE; DROP SCHEMA IF EXISTS attic CASCADE; DROP SCHEMA IF EXISTS postbox CASCADE")
        (testing "a page naming a module the app doesn't have is refused"
          (is (= :groundwork.server.main/bad-pages
                 (try (main/start-app! (assoc (make-app owner) :browser-modules {}))
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (testing "an owner role, or a database not migrated, is refused"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owns tables or passes the gates"
                                (main/start-app! (make-app owner)))))
        (with-out-str (main/migrate-app! (make-app owner) (make-database-settings owner) "groundwork_app"))
        (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
        (run-sql! owner-pool (str "INSERT INTO shop_emails (shop_email__address, shop_email__shop_id) VALUES ('anna@shop.test', '" shop-7 "')"))
        (database/run-in-transaction! owner-pool {:read-only? false}
                                      (fn [transaction]
                                        (let [roles-of-7 (doorman/add-scope! transaction scope-kinds :shop shop-7 paul nil)]
                                          (doorman/add-scope! transaction scope-kinds :shop shop-9 bob nil)
                                          ;; a member holds a set of roles, by their ids
                                          (doorman/add-membership! transaction [:shop shop-7] anna #{(get roles-of-7 "Staff")}))))
        (testing "an app route at an address groundwork serves is refused at the start"
          (is (thrown? Exception
                       (main/start-app! (assoc-in (make-app "groundwork_app") [:routes :app/health]
                                                  {:address "/health/live" :method :get :kind :provider
                                                   :rate-limit nil :handler (fn [_ _] {:status 200})})))))
        (testing "a reason with no text in every catalog refuses the start, named"
          (is (= {:type :dictionary/missing-texts :missing #{:orders/too-late}}
                 (try (main/start-app! (assoc-in (make-app "groundwork_app") [:handlers :orders/count :refusals] {:orders/too-late nil}))
                      (catch clojure.lang.ExceptionInfo e
                        {:type    (:type (ex-data e))
                         :missing (set (mapcat :missing-texts (vals (:problems (ex-data e)))))})))))
        (testing "a handler declaring groundwork's own reason is refused: groundwork adds it"
          (is (= :groundwork.server.main/bad-handlers
                 (try (main/start-app! (assoc-in (make-app "groundwork_app") [:handlers :orders/add :refusals]
                                                 {:groundwork/once-key-not-a-uuid nil}))
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (let [running     (main/start-app! (make-app "groundwork_app"))
              stopped?    (atom false)
              server-port (get-in running [:server :port])
              cookie      #(first (str/split (:set-cookie (doorman/sign-in! (:pool running) {:headers {}} %
                                                                    {:settings (get-in running [:settings :sessions])
                                                                     :tables [shops orders] :session-flags #{}})) #";"))
              anna-cookie (cookie anna)
              bob-cookie  (cookie bob)]
          (try
            (testing "a page in the request's language: the address's prefix, else the cookie, Accept-Language, the default"
              (let [lang-of #(second (re-find #"<html lang=\"([^\"]+)\">" (second %)))
                    get-page (fn [path headers]
                               (let [response (.send (HttpClient/newHttpClient)
                                                     (.build (reduce (fn [b [k v]] (.header b k v))
                                                                     (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port path)))
                                                                     headers))
                                                     (HttpResponse$BodyHandlers/ofString))]
                                 [(.statusCode response) (.body response)]))]
                (is (= "en" (lang-of (get-page "/orders/42" {}))))
                (let [[status html] (get-page "/de/orders/42" {})]
                  (is (= 200 status) "the prefix is taken off before routing")
                  (is (= "de" (lang-of [status html])))
                  (is (str/includes? html "\"language\":\"de\"") "the browser is told the page's language")
                  (is (str/includes? html (str "<link rel=\"alternate\" hreflang=\"en\" href=\"http://app.test/orders/42\">\n"
                                               "<link rel=\"alternate\" hreflang=\"de\" href=\"http://app.test/de/orders/42\">\n"
                                               "<link rel=\"alternate\" hreflang=\"x-default\" href=\"http://app.test/orders/42\">"))
                      "the same page in every language, at the app's origin"))
                (is (= "de" (lang-of (get-page "/orders/42" {"accept-language" "de-AT,de;q=0.9"}))))
                (is (= "en" (lang-of (get-page "/orders/42" {"accept-language" "de" "cookie" "lang=en"}))) "the cookie first")
                (is (= 404 (first (get-page "/de/nowhere" {}))))))
            (testing "the app's own routes"
              (run-sql! owner-pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop-7 "'), ('" shop-9 "')"))
              (testing ":person: the asker of the request's session, and a transaction as them, held by the gates"
                (is (= [200 (str anna " 1")] (take 2 (send-plain server-port "GET" "/app/orders" nil anna-cookie nil)))
                    "shop 7's order, not shop 9's")
                (is (= [200 " 0"] (take 2 (send-plain server-port "GET" "/app/orders" nil nil nil))) "nobody: no rows"))
              (testing ":person: a request that changes something comes from the app's own pages"
                (is (= 403 (first (send-plain server-port "POST" "/app/sign-in" nil nil nil))))
                (is (= 403 (first (send-plain server-port "POST" "/app/sign-in" "http://evil.test" nil nil))))
                (let [[status body set-cookie] (send-plain server-port "POST" "/app/sign-in" "http://app.test" nil nil)
                      new-cookie (first (str/split set-cookie #";"))]
                  (is (= [200 "signed in"] [status body]))
                  (is (= (str anna " 1") (second (send-plain server-port "GET" "/app/orders" nil new-cookie nil)))
                      "the session it made is Anna's")))
              (testing ":provider: no session, the raw body, its rate limit"
                (is (= [200 "got {\"type\":\"charge\"}"] (take 2 (send-plain server-port "POST" "/app/hook" nil nil "{\"type\":\"charge\"}"))))
                (is (= 200 (first (send-plain server-port "POST" "/app/hook" nil nil "x"))))
                (is (= 429 (first (send-plain server-port "POST" "/app/hook" nil nil "x"))) "the third in a minute"))
              (testing "a token's call: doorman finds its person, on a :person route and on the messages' route"
                (let [make-token (fn [cookie]
                                   (:text (doorman/make-token! (:pool running) {:request-method :post :headers {"origin" "http://app.test" "cookie" cookie}}
                                                               {:settings (get-in running [:settings :sessions]) :name "a script"
                                                                :ends-at  (.plus (java.time.Instant/now) (java.time.Duration/ofDays 1))})))
                      anna-token (make-token anna-cookie)
                      bob-token  (make-token bob-cookie)
                      call       (fn [path token cookie body]
                                   (let [response (.send (HttpClient/newHttpClient)
                                                         (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port path)))
                                                           token  (.header "authorization" (str "Bearer " token))
                                                           cookie (.header "cookie" cookie)
                                                           true   (-> (.POST (HttpRequest$BodyPublishers/ofString body)) (.build)))
                                                         (HttpResponse$BodyHandlers/ofString))]
                                     [(.statusCode response) (.body response)]))
                      orders-of  #(get (json/read-value (second (call "/app/call" % nil "{\"orders/all\":{}}"))) "orders/all")]
                  (is (= 1 (count (orders-of anna-token))) "Anna: shop 7's order, with no Origin: a token is no browser's")
                  (is (= 1 (count (orders-of bob-token))) "Bob: shop 9's")
                  (is (not= (orders-of anna-token) (orders-of bob-token)))
                  (is (= (refusal-of :doorman/signed-out nil) (dissoc (orders-of "app_not-a-token") "bridge/request-id"))
                      "a wrong token is nobody: signed out")
                  (is (= 1 (count (get (json/read-value (second (call "/groundwork/http" anna-token nil "{\"orders/all\":{}}"))) "orders/all")))
                      "the messages' own route takes a token too")
                  (is (= [403 "Not allowed."] (call "/app/call" anna-token bob-cookie "{\"orders/all\":{}}"))
                      "a cookie and a token at once: doorman's reason's text, in the request's language")
                  (is (= [200 (str anna " 1")] (take 2 (send-plain-with-token server-port "/app/orders" anna-token)))
                      "a :person route's asker, and its run-as-asker!, by the token")
                  (let [[first-answer second-answer third-answer]
                        (repeatedly 3 #(get (json/read-value (second (call "/app/call" anna-token nil "{\"orders/count\":{}}"))) "orders/count"))]
                    (is (every? int? [first-answer second-answer]))
                    (is (is-too-many? third-answer) "the handler's rate limit, {:max 2}, counted per token"))
                  (is (int? (get (json/read-value (second (call "/app/call" bob-token nil "{\"orders/count\":{}}"))) "orders/count"))
                      "another token has its own")))
              (run-sql! owner-pool "DELETE FROM orders"))
            (testing "each language's catalog, where the browser fetches it, as the server reads it"
              (let [response (.send (HttpClient/newHttpClient)
                                    (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/groundwork_test/i18n/de.json"))))
                                    (HttpResponse$BodyHandlers/ofString))]
                (is (= [200 "no-cache"] [(.statusCode response) (.orElse (.firstValue (.headers response) "cache-control") nil)]))
                (is (= "Bestellungen" (get (json/read-value (.body response)) "orders/title"))))
              (testing "and the server's own texts outside a request: the language bound by hand, tr inside"
                (is (= "3 Bestellungen" (dictionary/with-language (:texts running) "de" (dictionary/tr :orders/count {:count 3}))))
                (is (= "1 order" (dictionary/with-language (:texts running) "en" (dictionary/tr :orders/count {:count 1}))))))
            (testing "a page opened fresh: the shell, with the browser's settings in it"
              (let [[status html] (send-request server-port "GET" "/orders/42" nil nil)]
                (is (= 200 status))
                (is (str/includes? html "<title>Shops &lt;test&gt;</title>"))
                (is (str/includes? html "\"version\":\"test-1\""))
                (is (str/includes? html "\"ws-reopen-after-ms\":1000") "the ws reopen delay, from the :server settings")
                (is (str/includes? html "\"sse-reconnect-after-ms\":3000") "and the sse one")
                (is (str/includes? html "\"doorbell\":{\"gather-changes-ms\":50,\"keep-unwatched-answers-ms\":300000}")
                    "the question store's settings")
                (is (str/includes? html "\"wire\":{\"most-shapes-kept\":1000,\"most-keys-kept\":10000}")
                    "the browser's wire settings, from :browser-wire")
                (is (str/includes? html "\"retry-writes\":{\"times\":1,\"wait-ms\":1000}")
                    "how a write that got no answer is retried, from :run-once")
                (is (str/includes? html "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"))
                (is (str/includes? html "\"orders/page\":{\"address\":\"/orders/:order-id\",\"title\":\"Order\"}"))
                (is (str/includes? html "\"sse\":\"/groundwork/sse\""))
                (is (re-find #"<script src=\"/js/main.0A1B2C3D4E5F.js\" nonce=\"[^\"]+\" defer></script>" html)
                    "the script the build's manifest lists")
                (is (not (str/includes? html "admin.6F5E4D3C2B1A.js")) "main only: a module loads on demand")
                (is (str/includes? html "\"module-stylesheets\":{\"admin\":\"/css/admin.1A2B3C4D5E6F.css\"}")
                    "each module's stylesheet bundle, for the loader")
                (is (str/includes? html "\"languages\":{\"languages\":[{\"code\":\"en\",\"name\":\"English\"},{\"code\":\"de\",\"name\":\"Deutsch\"}],\"default\":\"en\",\"cookie-name\":\"lang\",\"preview-languages\":[],\"fallback-language\":\"en\",\"catalogs-path\":\"groundwork_test/i18n\"}")
                    "dictionary's settings")
                (is (str/includes? html "\"admin/page\":{\"address\":\"/admin\",\"title\":\"Admin\",\"module\":\"admin\"}")
                    "a page names its module")
                (is (str/includes? html "<link rel=\"stylesheet\" href=\"/css/main.9F8E7D6C5B4A.css\">")
                    "the stylesheet bundle its manifest lists")))
            (testing "the shell's headers: fixed ones and the :page settings'"
              (let [headers (.headers (.send (HttpClient/newHttpClient)
                                             (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/orders/42"))))
                                             (HttpResponse$BodyHandlers/discarding)))
                    header  #(.orElse (.firstValue headers %) nil)]
                (is (= ["no-store" "nosniff" "same-origin" "DENY"]
                       (map header ["cache-control" "x-content-type-options" "referrer-policy" "x-frame-options"])))
                (is (re-matches #"script-src 'nonce-[A-Za-z0-9+/=]{24}' 'strict-dynamic'; object-src 'none'" (header "content-security-policy"))
                    "the policy, a nonce of this answer's own in place of {nonce}")))
            (testing "the app's render-page: its title, description, head and body; a nonce per answer"
              (let [page-of   (fn [path cookie]
                                (let [response (.send (HttpClient/newHttpClient)
                                                      (.build (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port path)))
                                                                cookie (.header "cookie" cookie)))
                                                      (HttpResponse$BodyHandlers/ofString))]
                                  {:status (.statusCode response) :html (.body response)
                                   :nonce  (second (re-find #"'nonce-([^']+)'" (.orElse (.firstValue (.headers response) "content-security-policy") "")))}))
                    {:keys [status html nonce]} (page-of "/de/orders/42" anna-cookie)]
                (is (= 200 status))
                (is (str/includes? html "<meta name=\"description\" content=\"The page page\">"))
                (is (str/includes? html (str "<script nonce=\"" nonce "\">window.appStarted = true;</script>")) "the app's head, its script with the nonce")
                (is (str/includes? html (str "<script src=\"/js/main.0A1B2C3D4E5F.js\" nonce=\"" nonce "\" defer></script>")) "the shell's own scripts carry it")
                (is (str/includes? html (str "<div id=\"app\"><main>Bestellungen for " anna "</main></div>"))
                    "the body, in the request's language, for the request's asker")
                (is (not= nonce (:nonce (page-of "/orders/42" nil))) "each answer its own nonce")
                (is (str/includes? (:html (page-of "/orders/42" nil)) "<main>Orders for nobody</main>")))
              (testing "an address no page has: the app's not-found page, 404; not a GET: 404, nothing"
                (let [response (.send (HttpClient/newHttpClient)
                                      (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/nowhere"))))
                                      (HttpResponse$BodyHandlers/ofString))]
                  (is (= 404 (.statusCode response)))
                  (is (str/includes? (.body response) "<title>Not found</title>"))
                  (is (str/includes? (.body response) "<main>No such page</main>")))
                (is (= [404 ""] (take 2 (send-plain server-port "POST" "/nowhere" "http://app.test" nil "x"))))))
            (testing "files: a hashed one is kept for a year, any other asked again"
              (let [response (.send (HttpClient/newHttpClient)
                                    (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/js/main.0A1B2C3D4E5F.js"))))
                                    (HttpResponse$BodyHandlers/ofString))]
                (is (= ["start();" "public, max-age=31536000, immutable"]
                       [(.body response) (.orElse (.firstValue (.headers response) "cache-control") nil)])))
              (let [response (.send (HttpClient/newHttpClient)
                                    (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/js/main.0A1B2C3D4E5F.js")))
                                        (.header "accept-encoding" "gzip, br") (.build))
                                    (HttpResponse$BodyHandlers/ofInputStream))]
                (is (= ["gzip" "start();" "text/javascript"]
                       [(.orElse (.firstValue (.headers response) "content-encoding") nil)
                        (slurp (java.util.zip.GZIPInputStream. (.body response)))
                        (first (str/split (.orElse (.firstValue (.headers response) "content-type") "") #";"))])
                    "a browser that takes gzip gets the gzipped copy, as JavaScript"))
              (let [response (.send (HttpClient/newHttpClient)
                                    (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/robots.txt"))))
                                    (HttpResponse$BodyHandlers/ofString))]
                (is (= "no-cache" (.orElse (.firstValue (.headers response) "cache-control") nil)))))
            (testing "no page, no file: 404"
              (is (= 404 (first (send-request server-port "GET" "/nowhere" nil nil)))))
            (testing "an answer comes with the tables its steps read"
              (is (= [200 "{\"orders/all\":[],\"bridge/notes\":{\"orders/all\":{\"tables\":[\"orders\"]}}}"]
                     (send-request server-port "POST" "/groundwork/http" anna-cookie "{\"orders/all\":{}}"))))
            (testing "a rate limit: two a minute per session, a third is refused; another session has its own"
              (let [counted "{\"orders/count\":0,\"bridge/notes\":{\"orders/count\":{\"tables\":[]}}}"]
                (let [[first-send second-send [third-status third-body]]
                      (repeatedly 3 #(send-request server-port "POST" "/groundwork/http" anna-cookie "{\"orders/count\":{}}"))]
                  (is (= [[200 counted] [200 counted]] [first-send second-send]))
                  (is (= 200 third-status) "one POST carries many messages: the refusal is in the answer")
                  (is (is-too-many? (get (json/read-value third-body) "orders/count"))))
                (is (= [200 counted] (send-request server-port "POST" "/groundwork/http" bob-cookie "{\"orders/count\":{}}")))))
            (testing "doorbell: each line hears the version first, then every changed table, never a row"
              (let [anna-line  (open-sse server-port anna-cookie)
                    bob-line   (open-sse server-port bob-cookie)
                    ask-orders #(send-request server-port "POST" "/groundwork/http" % "{\"orders/all\":{}}")
                    orders-are #(str "{\"orders/all\":[" (str/join "," (map (fn [id] (str "\"" id "\"")) %))
                                     "],\"bridge/notes\":{\"orders/all\":{\"tables\":[\"orders\"]}}}")]
                (is (= "{\"groundwork/version\":\"test-1\"}" (read-next-data anna-line)))
                (is (= "{\"groundwork/version\":\"test-1\"}" (read-next-data bob-line)))
                (Thread/sleep 200)
                (is (= 2 (doorbell/count-open-lines (:doorbell running))))
                (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-71 "', '" shop-7 "')"))
                (is (= "{\"doorbell/changed\":{\"table\":\"orders\"}}" (read-next-data anna-line)))
                (is (= "{\"doorbell/changed\":{\"table\":\"orders\"}}" (read-next-data bob-line))
                    "Bob hears that orders changed, not which order")
                (testing "asking again, the gates give each person only their own shop's orders"
                  (is (= [200 (orders-are [order-71])] (ask-orders anna-cookie)))
                  (is (= [200 (orders-are [])] (ask-orders bob-cookie)) "shop 7's order never reaches Bob"))
                (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-91 "', '" shop-9 "')"))
                (is (= "{\"doorbell/changed\":{\"table\":\"orders\"}}" (read-next-data anna-line)))
                (is (= "{\"doorbell/changed\":{\"table\":\"orders\"}}" (read-next-data bob-line)))
                (is (= [200 (orders-are [order-91])] (ask-orders bob-cookie)))
                (is (= [200 (orders-are [order-71])] (ask-orders anna-cookie)) "shop 9's order never reaches Anna")
                (testing "every table that tells, tells every line"
                  (run-sql! owner-pool "INSERT INTO shops VALUES ('10000000-0000-4000-8000-000000000001')")
                  (is (= "{\"doorbell/changed\":{\"table\":\"shops\"}}" (read-next-data anna-line)))
                  (is (= "{\"doorbell/changed\":{\"table\":\"shops\"}}" (read-next-data bob-line))))
                (testing "one transaction's statements on one table tell it once"
                  (database/run-in-transaction! owner-pool {:read-only? false}
                                                (fn [transaction]
                                                  (run-sql! transaction "UPDATE shops SET shop__id = shop__id")
                                                  (run-sql! transaction "DELETE FROM shops WHERE shop__id = '10000000-0000-4000-8000-000000000001'")))
                  (is (= "{\"doorbell/changed\":{\"table\":\"shops\"}}" (read-next-data anna-line)))
                  (is (= :nothing (read-next-data anna-line))))
                (testing "a statement that changed no row tells nobody"
                  (run-sql! owner-pool "UPDATE orders SET order__shop_id = order__shop_id WHERE false")
                  (is (= :nothing (read-next-data anna-line))))
                (testing "what may I do: doorman's handler, as the asker; a membership changed rings every line"
                  ;; new lines: a read above that timed out still holds the old ones
                  (let [anna-line   (open-sse server-port anna-cookie)
                        bob-line    (open-sse server-port bob-cookie)
                        ask-actions #(send-request server-port "POST" "/groundwork/http" % "{\"doorman/my-actions\":{}}")
                        notes       #(str ",\"bridge/notes\":{\"doorman/my-actions\":{\"tables\":[" % "]}}}")
                        read-tables (str "\"doorman/membership-roles\",\"doorman/memberships\",\"doorman/role-actions\","
                                         "\"doorman/roles\",\"doorman/scopes\"")]
                    (is (= "{\"groundwork/version\":\"test-1\"}" (read-next-data anna-line)))
                    (is (= "{\"groundwork/version\":\"test-1\"}" (read-next-data bob-line)))
                    (is (= [200 (str "{\"doorman/my-actions\":[{\"scope-kind\":\"shop\",\"scope-id\":\"" shop-7 "\","
                                     "\"actions\":[\"orders/edit\",\"orders/see\"]}]" (notes read-tables))]
                           (ask-actions anna-cookie))
                        "Anna's shop and her role's actions; the notes name doorman's tables")
                    (is (= [200 (str "{\"doorman/my-actions\":[]" (notes ""))] (ask-actions nil))
                        "nobody signed in, and this app opens no action to anyone: none, and nothing read")
                    (Thread/sleep 200)
                    (database/run-in-transaction! owner-pool {:read-only? false}
                                                  (fn [transaction]
                                                    (let [staff (some #(when (= "Staff" (:name %)) (:role-id %))
                                                                      (doorman/list-roles transaction scope-kinds [:shop shop-9]))]
                                                      (doorman/add-membership! transaction [:shop shop-9] anna #{staff}))))
                    (let [changed #{"{\"doorbell/changed\":{\"table\":\"doorman/memberships\"}}"
                                    "{\"doorbell/changed\":{\"table\":\"doorman/membership-roles\"}}"}]
                      (is (= changed (set (repeatedly 2 #(read-next-data anna-line))))
                          "the tables her my-actions read, so her screen asks again")
                      (is (= changed (set (repeatedly 2 #(read-next-data bob-line))))))
                    (is (= 2 (count (re-seq #"scope-kind" (second (ask-actions anna-cookie))))) "now in two shops")))
                ;; not closed here: a read that timed out still holds them;
                ;; stopping the server ends both streams
                ))
            (testing "every write runs once, by its one-time key"
              (let [add-order    #(str "{\"orders/add\":{\"order/shop-id\":\"" %1 "\",\"test/wait-ms\":" %2 "}}")
                    count-orders #(:n (database/find-one-row owner-pool (database/make-query-from-sql-text "orders" "SELECT count(*) AS n FROM orders") {}))
                    kept-keys    #(:n (database/find-one-row owner-pool count-kept-keys {:run-once-key/key %}))
                    key-1        (random-uuid)
                    before       (count-orders)
                    [status-1 first-answer] (send-write server-port anna-cookie key-1 (add-order shop-7 0))]
                (is (= 200 status-1))
                (is (uuid? (parse-uuid (str (get first-answer "orders/add")))) "a new order's id")
                (testing "a repeat with the same key saves nothing and answers the same"
                  (is (= [200 first-answer] (send-write server-port anna-cookie key-1 (add-order shop-7 0))))
                  (is (= (inc before) (count-orders))))
                (testing "a new key is a new write"
                  (is (not= first-answer (second (send-write server-port anna-cookie (random-uuid) (add-order shop-7 0)))))
                  (is (= (+ 2 before) (count-orders))))
                (testing "the same key from someone else is refused, and runs nothing"
                  (is (= [200 {"orders/add" (refusal-of :groundwork/once-key-of-someone-else nil)}]
                         (update (send-write server-port bob-cookie key-1 (add-order shop-9 0)) 1 without-request-ids)))
                  (is (= (+ 2 before) (count-orders))))
                (testing "a repeat while the first still runs waits for it, then answers its answer"
                  (let [key-2      (random-uuid)
                        first-send (future (send-write server-port anna-cookie key-2 (add-order shop-7 700)))
                        _          (Thread/sleep 200)
                        repeat     (future (send-write server-port anna-cookie key-2 (add-order shop-7 0)))]
                    (is (= 200 (first @first-send)))
                    (is (= @first-send @repeat))
                    (is (= (+ 3 before) (count-orders)) "one order, not two")))
                (testing "a key that isn't a UUID is refused"
                  (is (= [200 {"orders/add" (refusal-of :groundwork/once-key-not-a-uuid nil)}]
                         (update (send-write server-port anna-cookie "seven" (add-order shop-7 0)) 1 without-request-ids)))
                  (is (= (+ 3 before) (count-orders))))
                (testing "a write without a key runs every time"
                  (send-write server-port anna-cookie nil (add-order shop-7 0))
                  (send-write server-port anna-cookie nil (add-order shop-7 0))
                  (is (= (+ 5 before) (count-orders))))
                (testing "a write whose answer doesn't fit its shape saves nothing"
                  (let [key-3 (random-uuid)]
                    (is (= [200 {"orders/add-badly" (refusal-of :bridge/failed nil)}]
                           (update (send-write server-port anna-cookie key-3
                                               (str "{\"orders/add-badly\":{\"order/shop-id\":\"" shop-7 "\"}}"))
                                   1 without-request-ids)))
                    (is (= (+ 5 before) (count-orders)))
                    (is (zero? (kept-keys key-3)) "and its key is not kept: a repeat runs again")))
                (testing "a read isn't keyed"
                  (let [key-4 (random-uuid)]
                    (is (= 200 (first (send-write server-port anna-cookie key-4 "{\"orders/all\":{}}"))))
                    (is (= 200 (first (send-write server-port anna-cookie key-4 "{\"orders/all\":{}}"))))
                    (is (zero? (kept-keys key-4)))))
                (testing "a key is forgotten after :keep-keys-ms: a repeat then runs again"
                  (Thread/sleep 1600)
                  (let [[_ again] (send-write server-port anna-cookie key-1 (add-order shop-7 0))]
                    (is (not= first-answer again))
                    (is (= (+ 6 before) (count-orders)))))
                (testing "groundwork's background job deletes the forgotten keys"
                  (Thread/sleep 1600)
                  (is (= 1 (kept-keys key-1)))
                  (let [kind (run-once/make-forget-keys-kind (get-in running [:settings :run-once]))]
                    (is (= :groundwork-once-keys (:system-actor kind)) "it names its system actor")
                    ;; as backroom runs it: one transaction on the app's pool, as the kind's actor
                    (doorman/run-as-system! (:pool running) (:system-actor kind) {:read-only? false}
                                            (fn [transaction] ((:run kind) {:transaction transaction} {}))))
                  (is (zero? (kept-keys key-1))))))
            (testing "one message's lines, bridge's, cellar's and the app's own, carry its request id and the person"
              (reset! lines [])
              (let [[status body] (send-request server-port "POST" "/groundwork/http" anna-cookie "{\"orders/broken\":{}}")]
                (is (= [200 {"orders/broken" (refusal-of :bridge/failed nil)}] [status (without-request-ids (json/read-value body))])
                    "a crash answers :bridge/failed; the request stays 200"))
              (let [said    (first (find-lines "test/about-to-break"))
                    unknown (first (find-lines "cellar/tables-read-unknown"))
                    failed  (first (find-lines "bridge/handler-failed"))]
                (is (some? (get-in said ["context" "request-id"])))
                (is (= [(get said "context")] (distinct (map #(get % "context") [said unknown failed])))
                    "the same context on all three: one request id")
                (is (= (str anna) (get-in failed ["context" "person-id"])) "added by doorman's session step")
                (is (= ["info" "warn" "error"] (map #(get % "level") [said unknown failed])))
                (is (= "the app's step broke" (get-in failed ["error" "message"])))))
            (testing "the errors route: only with its token; the kept errors after ?since="
              (let [ask-errors (fn [token query]
                                 (let [response (.send (HttpClient/newHttpClient)
                                                       (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/groundwork/errors" query)))
                                                         token (.header "authorization" (str "Bearer " token))
                                                         true  (.build))
                                                       (HttpResponse$BodyHandlers/ofString))]
                                   [(.statusCode response) (.body response)]))]
                (is (= 401 (first (ask-errors nil "?since=0"))))
                (is (= 401 (first (ask-errors "a-wrong-token" "?since=0"))))
                (is (= 400 (first (ask-errors "the-errors-token" ""))) "since is said")
                (is (= 400 (first (ask-errors "the-errors-token" "?since=-1"))))
                (let [[status body] (ask-errors "the-errors-token" "?since=0")
                      errors        (json/read-value body)
                      last-seq      (get (peek errors) "seq")]
                  (is (= 200 status))
                  (is (= "bridge/handler-failed" (get (peek errors) "event")))
                  (is (= ["seq" "time" "level" "event" "context" "data" "error"] (vec (keys (peek errors))))
                      "each as written, its number first")
                  (is (= [200 "[]"] (ask-errors "the-errors-token" (str "?since=" last-seq))) "none after the last")
                  (testing "a browser's error, sent as a message, is kept with them, with the person"
                    (let [send-error #(send-request server-port "POST" "/groundwork/http" anna-cookie
                                                    (str "{\"groundwork/browser-error\":{\"message\":\"x is undefined\","
                                                         "\"stack\":\"at start (main.js:1)\",\"url\":\"http://app.test/orders/42\"}}"))]
                      (is (= [200 "{\"groundwork/browser-error\":true}"] (send-error)))
                      (let [[status body] (ask-errors "the-errors-token" (str "?since=" last-seq))
                            [kept]        (json/read-value body)]
                        (is (= 200 status))
                        (is (= ["error" "browser/uncaught" (str anna)
                                {"browser/message" "x is undefined" "browser/stack" "at start (main.js:1)"
                                 "browser/url"     "http://app.test/orders/42"}]
                               [(get kept "level") (get kept "event") (get-in kept ["context" "person-id"]) (get kept "data")])))
                      (testing "its rate limit, from the :errors settings: two a minute per asker"
                        (is (= [200 "{\"groundwork/browser-error\":true}"] (send-error)))
                        (let [[status body] (send-error)]
                          (is (= 200 status))
                          (is (is-too-many? (get (json/read-value body) "groundwork/browser-error")))))
                      (testing "from another site: refused by doorman's session step, not written"
                        (let [before (count (find-lines "browser/uncaught"))
                              answer (.body (.send (HttpClient/newHttpClient)
                                                   (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/groundwork/http")))
                                                       (.header "origin" "https://evil.test")
                                                       (.POST (HttpRequest$BodyPublishers/ofString
                                                               "{\"groundwork/browser-error\":{\"message\":\"m\",\"stack\":null,\"url\":\"u\"}}"))
                                                       (.build))
                                                   (HttpResponse$BodyHandlers/ofString)))]
                          (is (str/includes? answer "error"))
                          (is (= before (count (find-lines "browser/uncaught")))))))))))
            (testing "files: an upload asks doorman's session step, then the app's :may-upload?"
              (let [png    (byte-array (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A] (range 32)))
                    body   (byte-array (concat (.getBytes (str "--b\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"door.png\"\r\n"
                                                               "Content-Type: image/png\r\n\r\n"))
                                               png
                                               (.getBytes "\r\n--b--\r\n")))
                    upload (fn [origin cookie]
                             (let [response (.send (HttpClient/newHttpClient)
                                                   (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/groundwork/files")))
                                                     origin (.header "origin" origin)
                                                     cookie (.header "cookie" cookie)
                                                     true   (.header "content-type" "multipart/form-data; boundary=b")
                                                     true   (-> (.POST (HttpRequest$BodyPublishers/ofByteArray body)) (.build)))
                                                   (HttpResponse$BodyHandlers/ofString))]
                               [(.statusCode response) (json/read-value (.body response))]))]
                (is (= [403 {"error" "Not allowed."}] (upload "http://app.test" nil)) "nobody signed in")
                (is (= [403 {"error" "Not allowed."}] (upload "http://app.test" bob-cookie)) "the app's :may-upload? says no to Bob")
                (is (= [403 {"error" "Not allowed."}] (upload "https://evil.test" anna-cookie)) "from another site")
                (if-not s3-endpoint
                  (println "groundwork.server.main.api-test: the stored upload skipped, GROUNDWORK_S3_TEST_ENDPOINT is not set")
                  (let [uri    (URI. s3-endpoint)
                        client (aws/client {:api :s3 :region "us-east-1"
                                            :endpoint-override {:protocol (keyword (.getScheme uri)) :hostname (.getHost uri) :port (.getPort uri)}
                                            :credentials-provider (aws-credentials/basic-credentials-provider
                                                                   {:access-key-id     (System/getenv "GROUNDWORK_S3_TEST_ACCESS_KEY")
                                                                    :secret-access-key (System/getenv "GROUNDWORK_S3_TEST_SECRET_KEY")})})
                        _      (aws/invoke client {:op :CreateBucket :request {:Bucket "groundwork-test"}})
                        [status {[stored] "stored"}] (upload "http://app.test" anna-cookie)]
                    (aws/stop client)
                    (is (= 200 status))
                    (is (str/starts-with? (get stored "key") "orders/photos/"))
                    (is (= "image/png" (:file/content-type (attic/find-file (:pool running) (get stored "key"))))
                        "recorded in attic.files, as the app's role")
                    (is (= (seq png) (seq (attic/read-file-bytes (:attic running) (get stored "key")))))))))
            (testing "email: queued in a write's transaction, sent by the workers; its bounce handed to the app by tag"
              (let [email {:key "order-confirmation/71" :tag :orders/confirmation :to ["anna@shop.test"]
                           :from "orders@shop.test" :reply-to nil :subject "Your order" :html nil :text "Thank you."
                           :metadata {"order" "71"} :attachments [] :stream "outbound"}]
                (reset! lines [])
                (is (= :queued (database/run-in-transaction! (:pool running) {:read-only? false}
                                 #(postbox/queue-email! (:postbox running) % email))))
                (loop [n 0]
                  (when (and (< n 100) (not= "sent" (:email/state (postbox/find-email (:pool running) "order-confirmation/71"))))
                    (Thread/sleep 100)
                    (recur (inc n))))
                (let [{:email/keys [state message-id]} (postbox/find-email (:pool running) "order-confirmation/71")]
                  (is (= "sent" state) "by groundwork's workers, postbox's kind among them")
                  (is (= 1 (count (find-lines "postbox/logged-not-sent"))))
                  (let [post (fn [token]
                               (.statusCode (.send (HttpClient/newHttpClient)
                                                   (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/postbox/delivery?token=" token)))
                                                       (.header "content-type" "application/json")
                                                       (.POST (HttpRequest$BodyPublishers/ofString
                                                               (json/write-value-as-string {"RecordType" "Bounce" "Type" "HardBounce"
                                                                                            "Tag" "orders/confirmation" "MessageID" message-id
                                                                                            "Email" "anna@shop.test" "Metadata" {"order" "71"}})))
                                                       (.build))
                                                   (HttpResponse$BodyHandlers/ofString))))]
                    (is (= 401 (post "a-wrong-token-0123456")))
                    (is (= [] @delivery-events))
                    (is (= 200 (post "the-webhook-token-0123")))
                    (is (= [{:kind :bounce :tag :orders/confirmation :message-id message-id :email-key "order-confirmation/71"
                             :address "anna@shop.test" :bounce-type "HardBounce" :inactive? false :metadata {"order" "71"}
                             :marked 1 :orders-seen 0}]
                           @delivery-events)
                        "run as the system actor :postbox: it marks the shop's address, and sees none of the shop's orders")
                    (is (= [true] (map :shop-email/bounced (database/find-rows owner-pool (database/make-query-from-sql-text
                                                                                          "bounced" "SELECT shop_email__bounced FROM shop_emails")
                                                                               {})))))
                  (is (= [] (doorman/find-system-leaks (:pool running) [shops orders shop-emails] :postbox {:orders [order-71 order-91]}))
                      "the system leak test, on the app's own role"))))
            (testing "the metrics: only with their token; every library's on the app's one meter, and groundwork's"
              (let [ask-metrics (fn [token]
                                  (let [response (.send (HttpClient/newHttpClient)
                                                        (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/metrics")))
                                                          token (.header "authorization" (str "Bearer " token))
                                                          true  (.build))
                                                        (HttpResponse$BodyHandlers/ofString))]
                                    [(.statusCode response) (.body response)]))
                    [status text] (ask-metrics "the-metrics-token")]
                (is (= 401 (first (ask-metrics nil))))
                (is (= 401 (first (ask-metrics "a-wrong-token"))))
                (is (= 200 status))
                (doseq [pattern [#"bridge_messages_total\{name=\"orders/all\",outcome=\"ok\",pipe=\"http\"\} [1-9]"
                                 #"bridge_open_lines\{pipe=\"sse\"\} "
                                 #"cellar_query_seconds_count\{query=\"groundwork/server/main/claim_once_key.sql\"\} [1-9]"
                                 #"cellar_pool_connections\{state=\"idle\"\} "
                                 #"doorbell_listening 1"
                                 #"doorbell_changes_total\{table=\"orders\"\} [1-9]"
                                 #"backroom_dispatcher_failures_total 0"
                                 #"# TYPE attic_files_stored_total counter"
                                 #"postbox_emails_sent_total\{tag=\"orders/confirmation\"\} 1"
                                 #"logbook_lines_total\{level=\"info\"\} [1-9]"
                                 #"logbook_errors_kept [1-9]"
                                 #"groundwork_build_info\{version=\"test-1\"\} 1"
                                 #"groundwork_stage\{stage=\"started\"\} 1"
                                 #"jvm_threads_current [1-9]"]]
                  (is (re-find pattern text) (str pattern)))))
            (testing "a visitor's session: from the app's pages only; a cookie once, none for someone signed in"
              (let [start-visitor (fn [origin cookie]
                                    (let [response (.send (HttpClient/newHttpClient)
                                                          (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/groundwork/visitor")))
                                                            origin (.header "origin" origin)
                                                            cookie (.header "cookie" cookie)
                                                            true   (-> (.POST (HttpRequest$BodyPublishers/noBody)) (.build)))
                                                          (HttpResponse$BodyHandlers/ofString))]
                                      [(.statusCode response) (.body response)
                                       (.orElse (.firstValue (.headers response) "set-cookie") nil)]))
                    [status body set-cookie] (start-visitor "http://app.test" nil)]
                (is (= 403 (first (start-visitor nil nil))))
                (is (= 403 (first (start-visitor "https://evil.test" nil))))
                (is (= 200 status))
                (is (uuid? (parse-uuid (str (get (json/read-value body) "visitor-id")))))
                (is (str/starts-with? (str set-cookie) "groundwork_session="))
                (is (= [200 "{\"visitor-id\":null}" nil] (start-visitor "http://app.test" anna-cookie))
                    "Anna is signed in: no visitor, no new cookie")
                (is (= 200 (first (start-visitor "http://app.test" nil))) "the fifth POST of this address, its :max")
                (is (= [429 "Too many requests." nil] (start-visitor "http://app.test" nil))
                    "over the :visitors :rate-limit, by address: no session, no cookie")))
            (testing "the REPL: groundwork's tools in study.tools"
              (with-open [connection (nrepl/connect :port (study/find-repl-port (:study running)))]
                (let [client (nrepl/client connection 10000)
                      eval!  #(first (nrepl/response-values (nrepl/message client {:op "eval" :code %})))]
                  (let [count-ok #(or (some->> (meter/write-metrics-text (:meter running))
                                                (re-find #"bridge_messages_total\{name=\"orders/all\",outcome=\"ok\",pipe=\"http\"\} (\d+)")
                                                second parse-long)
                                      0)
                        before   (count-ok)
                        ask-all (fn [person] (set (map str (:orders/all (eval! (str "(study.tools/ask-as-person {:orders/all {}} {:person-id #uuid \""
                                                                                                    person "\" :visitor-id nil :session-flags #{}})"))))))
                        pauls   (ask-all paul)
                        bobs    (ask-all bob)]
                    (is (= (+ 2 before) (count-ok)) "each ask is counted on the app's meter, as the same message over http")
                    (is (and (contains? pauls (str order-71)) (not (contains? pauls (str order-91))))
                        "as Paul, shop 7's founder, through the check and the gates: only his shop's orders")
                    (is (and (contains? bobs (str order-91)) (not (contains? bobs (str order-71))))
                        "as Bob: none of shop 7's"))
                  (is (= {:handler :orders/all :answer :doorman/not-found}
                         (select-keys (eval! (str "(study.tools/why-refused {:person-id #uuid \"" (random-uuid) "\" :visitor-id nil :session-flags #{}} :orders/all {})"))
                                      [:handler :answer]))
                      "someone in no shop: doorman's explain-refusal says why")
                  (let [{:keys [shape doc]} (eval! "(study.tools/explain-setting [:server :ping-every-ms])")]
                    (is (some? shape))
                    (is (str/includes? doc "every key required") "the text of bridge's settings shape"))
                  (is (= "No setting is at this path." (:doc (eval! "(study.tools/explain-setting [:server :nope])")))))))
            (testing "health: plain GETs, no origin, no session, no rate limit, never cached"
              (is (= [200 "{\"status\":\"live\",\"version\":\"test-1\"}" "no-store"]
                     (ask-health server-port "/health/live")))
              (is (= (repeat 20 [200 "{\"status\":\"ready\",\"version\":\"test-1\"}" "no-store"])
                     (repeatedly 20 #(ask-health server-port "/health/ready")))))
            (testing "no answer from the database (every connection held): ready answers 503 at its timeout"
              (let [release (promise)
                    holders (doall (repeatedly 4 #(future (database/run-in-transaction! (:pool running) {:read-only? false} (fn [_] @release)))))]
                (try
                  (Thread/sleep 300)
                  (let [started-at (System/currentTimeMillis)
                        answer     (take 2 (ask-health server-port "/health/ready"))
                        took-ms    (- (System/currentTimeMillis) started-at)]
                    (is (= [503 "{\"status\":\"not-ready\",\"version\":\"test-1\",\"reason\":\"the database did not answer within 500 ms\"}"]
                           answer))
                    (is (<= 500 took-ms 2000)))
                  (is (= 200 (first (ask-health server-port "/health/live"))) "live asks no database")
                  (finally (deliver release nil) (run! deref holders))))
              (is (= 200 (first (ask-health server-port "/health/ready"))) "ready again once a connection is free"))
            (testing "the database unreachable: ready answers 503 within its timeout, live 200"
              (run-sql! owner-pool "ALTER ROLE groundwork_app NOLOGIN")
              (run-sql! owner-pool "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'groundwork_app'")
              (let [started-at        (System/currentTimeMillis)
                    [status body]     (ask-health server-port "/health/ready")
                    took-ms           (- (System/currentTimeMillis) started-at)]
                (is (= 503 status))
                (is (re-find #"\"status\":\"not-ready\",\"version\":\"test-1\",\"reason\":\"the database (did not answer within 500 ms|answered with an error)\"" body))
                (is (< took-ms 2000) "the timeout, not the pool's wait for a connection"))
              (is (= 503 (first (ask-health server-port "/health/ready"))) "a second check waits on the first")
              (is (= 200 (first (ask-health server-port "/health/live"))))
              (run-sql! owner-pool "ALTER ROLE groundwork_app LOGIN")
              (is (= 200 (loop [tries 60]
                           (let [status (first (ask-health server-port "/health/ready"))]
                             (if (or (= 200 status) (zero? tries)) status (do (Thread/sleep 250) (recur (dec tries)))))))
                  "ready again once the database lets the app in"))
            (testing "stopping: ready answers 503 before the server stops taking requests; live 200"
              (let [stopping (future (main/stop-app! running))]
                (reset! stopped? true)
                (Thread/sleep 300)
                (is (= [503 "{\"status\":\"not-ready\",\"version\":\"test-1\",\"reason\":\"the app is stopping\"}"]
                       (take 2 (ask-health server-port "/health/ready"))))
                (is (= 200 (first (ask-health server-port "/health/live"))))
                @stopping))
            (finally (when-not @stopped? (main/stop-app! running)))))
        (finally (database/stop-pool! owner-pool))))))
