(ns house.server.main.api-test
  "The whole server, started as production starts it: migrate-app! as the
   owner, then start-app! as a role that owns nothing. The page shell, a
   message with its notes, and doorbell: a new order is told to every open
   sse line as only its table, and each person's asking again sees, through the
   gates, only their own shop's orders. One message's lines, written by
   mailroom, by cellar through doorbell and by the app's own step, carry
   its request id and the person doorman found; the errors route hands a
   monitor the kept errors, only with its token; a browser's error, sent
   as a message, is kept with them. Then the health checks: live and
   ready, a database that doesn't answer or lets nobody in, and stopping;
   the metrics route, a visitor's session, the REPL's tools.
   Runs when
   HOUSE_SQL_TEST_PORT is set."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as database]
   [dictionary.api :as dictionary]
   [doorbell.api :as doorbell]
   [doorman.api :as doorman]
   [house.server.main.api :as main]
   [house.server.main.run-once :as run-once]
   [jsonista.core :as json]
   [logbook.api :as logbook]
   [mailroom.api :as mailroom]
   [meter.api :as meter]
   [nrepl.core :as nrepl]
   [study.api :as study])
  (:import
   (java.io BufferedReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "HOUSE_SQL_TEST_PORT") parse-long))
(def ^:private owner (System/getenv "HOUSE_SQL_TEST_USER"))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops :parent nil :single? false
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}
           :denies  []}}))

(def ^:private shops
  {:table    :shops
   :columns  [[:shop/id :uuid {:primary-key? true}]]
   :indexes  []
   :access   :public
   :doorbell :tell})

(def ^:private orders
  {:table    :orders
   :columns  [[:order/id :uuid {:primary-key? true :default :random-uuid}]
              [:order/shop-id :uuid {:references :shop/id}]]
   :indexes  []
   :access   [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}]
   :doorbell :tell})

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
  (database/make-query-from-sql-text "one-time keys kept" "SELECT count(*) AS n FROM house.run_once_keys WHERE run_once_key__key = :run-once-key/key"))

(def ^:private find-untyped
  (database/make-query-from-sql-text "a param Postgres can't type" "SELECT :x IS NULL AS b"))

(def ^:private lines
  "The lines the app's logbook wrote, as JSON data."
  (atom []))

(defn- find-lines [event]
  (filterv #(= event (get % "event")) @lines))

(def ^:private handlers
  {:orders/count {:action :orders/see :params-shape [:map] :answer-shape :int :rate-limit {:max 2 :per-ms 60000}
                  :run-steps-in nil
                  :steps  [{:name :answer-count :enter #(mailroom/add-answer-to-context % 0)}]}
   :orders/all {:action :orders/see :params-shape [:map] :answer-shape [:vector :uuid] :rate-limit nil :run-steps-in nil
                :steps  [{:name  :answer-all-orders
                          :enter #(mailroom/add-answer-to-context
                                   % (mapv :order/id (database/find-rows (:doorman/transaction %) find-all-orders {})))}]}
   :orders/add {:action :orders/edit :params-shape [:map [:order/shop-id :uuid] [:test/wait-ms :int]] :answer-shape :uuid
                :rate-limit nil :run-steps-in nil
                :steps  [{:name  :add-order
                          :enter (fn [{:mailroom/keys [message-data] :as context}]
                                   (Thread/sleep (long (:test/wait-ms message-data)))
                                   (mailroom/add-answer-to-context
                                    context (:order/id (database/find-one-row (:doorman/transaction context) insert-order
                                                                              (select-keys message-data [:order/shop-id])))))}]}
   :orders/add-badly {:action :orders/edit :params-shape [:map [:order/shop-id :uuid]] :answer-shape :uuid
                      :rate-limit nil :run-steps-in nil
                      :steps  [{:name  :add-order-and-answer-a-number
                                :enter (fn [{:mailroom/keys [message-data] :as context}]
                                         (database/find-one-row (:doorman/transaction context) insert-order message-data)
                                         (mailroom/add-answer-to-context context 42))}]}
   :orders/broken {:action :orders/see :params-shape [:map] :answer-shape :int :rate-limit nil :run-steps-in nil
                   :steps  [{:name  :say-and-ask-untyped
                             :enter (fn [{:mailroom/keys [log] :as context}]
                                      (log :info :test/about-to-break {})
                                      (database/find-one-row (:doorman/transaction context) find-untyped {:x "a"})
                                      context)}
                            {:name :break :enter (fn [_] (throw (ex-info "the app's step broke" {})))}]}})

(def ^:private files-folder
  "A release build's folder, as the deploy leaves it: a manifest and one hashed file."
  (let [folder (.toFile (java.nio.file.Files/createTempDirectory "house-files" (make-array java.nio.file.attribute.FileAttribute 0)))]
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
   :metrics           {:path "/metrics" :token "the-metrics-token"}
   :study             {:mode :development :bind-address "127.0.0.1" :port 0 :middleware [] :port-file nil}
   :visitors          {:path "/house/visitor"}
   :errors            {:path "/house/errors" :token "the-errors-token" :browser-rate-limit {:max 2 :per-ms 60000}}
   :migrate-at-start? false
   :browser-build     :built
   :server            {:ip "127.0.0.1" :port 0 :proxy-protocol :disable :client-address-header nil :server-header nil
                       :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
                       :addresses {:http "/house/http" :sse "/house/sse" :ws "/house/ws"}
                       :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
                       :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
                       :rate-limit-buckets-before-cleanup 10000
                       :metric-buckets {:answer-seconds [0.01 0.1 1]}}
   :database          (make-database-settings user)
   :migration         {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}
   :sessions          {:cookie-name "house_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                       :allowed-origin "http://app.test" :secure-cookie? false}
   :my-actions        {:rate-limit {:max 60 :per-ms 60000}}
   :run-once          {:keep-keys-ms 1500
                       :forget-keys  {:cron "0 * * * *" :timeout-ms 10000 :attempts 1}
                       :retry-writes {:times 1 :wait-ms 1000}}
   :background-jobs   {:workers 1 :poll-ms 1000
                       :retry-first-wait-ms 30000 :retry-wait-factor 2 :retry-max-wait-ms 3600000
                       :stuck-after-time-limit-ms 60000 :forget-done-after-ms 604800000
                       :forget-done-every-ms 3600000 :stop-wait-ms 5000
                       :metric-buckets {:job-run-seconds [0.1 1 10]}}
   :doorbell          {:store     {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
                       :listening {:batch-window-ms 50 :check-stopped-every-ms 500
                                   :reconnect-after-ms 1000 :stop-wait-ms 2000}}
   :languages         {:languages ["en" "de"] :default "en" :cookie-name "lang"
                       :preview-languages [] :fallback-language "en" :catalogs-path "house_test/i18n"}
   :page              {:title "Shops <test>" :viewport "width=device-width, initial-scale=1"
                       :content-security-policy :none :referrer-policy "same-origin" :frame-options "DENY"
                       :files {:folder files-folder :hashed-name-min-hex-digits 8 :hashed-file-cache-seconds 31536000}}
   :health            {:live-path "/health/live" :ready-path "/health/ready"
                       :database-timeout-ms 500 :not-ready-before-stop-ms 1000}})

(defn- make-app [user]
  {:settings (make-settings user) :tables [shops orders] :changes [] :scope-kinds scope-kinds
   :handlers handlers :pages {:orders/page {:address "/orders/:order-id" :title "Order"}
                              :admin/page  {:address "/admin" :title "Admin" :module "admin"}}
   :browser-entry 'app.browser.main/start!
   :browser-modules {:admin {:entries '[app.browser.admin] :depends-on #{:main}}}
   :background-job-kinds [] :schedules []})

(defn- run-sql! [pool text] (database/run-statement! pool (database/make-query-from-sql-text "test" text) {}))

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

(defn- send-write
  "A POST of a write with its one-time key in the idempotency-key header:
   [status the answer's named data, parsed]."
  [server-port cookie once-key body]
  (let [response (.send (HttpClient/newHttpClient)
                        (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/house/http")))
                          true     (.header "origin" "http://app.test")
                          true     (.header "cookie" cookie)
                          once-key (.header "idempotency-key" (str once-key))
                          true     (-> (.POST (HttpRequest$BodyPublishers/ofString body)) (.build)))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (dissoc (json/read-value (.body response)) "mailroom/notes")]))

(defn- open-sse [server-port cookie]
  (io/reader (.body (.send (HttpClient/newHttpClient)
                           (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/house/sse")))
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

(deftest the-settings-are-checked-all-at-once
  (let [problems (try (main/check-settings (-> (make-settings "x") (dissoc :version) (assoc-in [:server :port] "80")
                                               (update :server dissoc :addresses :ping-every-ms)))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (contains? problems :version))
    (is (contains? (:server problems) :port))
    (is (= #{:port :addresses :ping-every-ms} (set (keys (:server problems))))
        "mailroom's settings have no defaults: each missing one is named"))
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
        "wire's settings, the JVM's and the browser's, have no defaults: house makes the one wire from them"))
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
  (is (thrown? clojure.lang.ExceptionInfo
               (main/check-settings (assoc (make-settings "x") :browser-build {:watch {:entry 'app.browser.main/start!}})))
      "the watch options leave out the entry and the folder: they come from the app and :page")
  (is (thrown? clojure.lang.ExceptionInfo
               (main/check-settings (assoc (make-settings "x") :browser-build {:watch {:log (fn [_ _ _])}})))
      "and the log: house passes its own"))

(deftest a-table-that-writes-what-house-puts-is-refused
  (let [{:keys [type problems]} (try (main/make-ready-tables {:tables      [(assoc shops :on-change nil) (assoc orders :gates nil)]
                                                              :scope-kinds scope-kinds})
                                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :house.server.main/bad-tables type))
    (is (= [":shops writes its own :on-change, which house puts on it: doorbell's, from its :doorbell"
            ":orders writes its own :gates, which house puts on it: doorman's, from its :access"]
           problems)
        "each named, not overwritten without a word"))
  (is (= [nil {:function "doorbell.tell" :arguments []}]
         ((juxt :gates :on-change) (first (main/make-ready-tables {:tables [shops] :scope-kinds scope-kinds}))))
      "one that leaves them out gets doorman's and doorbell's"))

(deftest the-whole-server
  (if-not port
    (println "house.server.main.api-test: skipped, HOUSE_SQL_TEST_PORT is not set")
    (let [owner-pool (start-pool! owner)]
      (try
        (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE; DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS house CASCADE")
        (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'house_app') THEN CREATE ROLE house_app LOGIN; END IF; END $$")
        (testing "an app's handler named :doorman/my-actions is refused: it is house's"
          (is (= :house.server.main/handler-name-taken
                 (try (main/start-app! (-> (make-app owner)
                                           (assoc-in [:settings :migrate-at-start?] true)
                                           (assoc-in [:handlers :doorman/my-actions] (:orders/count handlers))))
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE; DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS house CASCADE")
        (testing "a page naming a module the app doesn't have is refused"
          (is (= :house.server.main/bad-pages
                 (try (main/start-app! (assoc (make-app owner) :browser-modules {}))
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (testing "an owner role, or a database not migrated, is refused"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owns tables or passes the gates"
                                (main/start-app! (make-app owner)))))
        (with-out-str (main/migrate-app! (make-app owner) (make-database-settings owner) "house_app"))
        (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
        (database/run-in-transaction! owner-pool {:read-only? false}
                                      (fn [transaction]
                                        (doorman/add-scope! transaction scope-kinds :shop shop-7 paul nil)
                                        (doorman/add-scope! transaction scope-kinds :shop shop-9 bob nil)
                                        (doorman/add-membership! transaction [:shop shop-7] anna "Staff")))
        (let [running     (main/start-app! (make-app "house_app"))
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
            (testing "each language's catalog, where the browser fetches it, as the server reads it"
              (let [response (.send (HttpClient/newHttpClient)
                                    (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/house_test/i18n/de.json"))))
                                    (HttpResponse$BodyHandlers/ofString))]
                (is (= [200 "no-cache"] [(.statusCode response) (.orElse (.firstValue (.headers response) "cache-control") nil)]))
                (is (= "Bestellungen" (get (json/read-value (.body response)) "orders/title"))))
              (testing "and the server's own texts, the language a value"
                (is (= "3 Bestellungen" (dictionary/make-text (:texts running) "de" :orders/count {:count 3})))
                (is (= "1 order" (dictionary/make-text (:texts running) "en" :orders/count {:count 1})))))
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
                (is (str/includes? html "\"sse\":\"/house/sse\""))
                (is (str/includes? html "<script src=\"/js/main.0A1B2C3D4E5F.js\" defer></script>")
                    "the script the build's manifest lists")
                (is (not (str/includes? html "admin.6F5E4D3C2B1A.js")) "main only: a module loads on demand")
                (is (str/includes? html "\"module-stylesheets\":{\"admin\":\"/css/admin.1A2B3C4D5E6F.css\"}")
                    "each module's stylesheet bundle, for the loader")
                (is (str/includes? html "\"languages\":{\"languages\":[\"en\",\"de\"],\"default\":\"en\",\"cookie-name\":\"lang\",\"preview-languages\":[],\"fallback-language\":\"en\",\"catalogs-path\":\"house_test/i18n\"}")
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
                (is (= ["no-store" "nosniff" "same-origin" "DENY" nil]
                       (map header ["cache-control" "x-content-type-options" "referrer-policy" "x-frame-options"
                                    "content-security-policy"])))))
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
              (is (= [200 "{\"orders/all\":[],\"mailroom/notes\":{\"orders/all\":{\"tables\":[\"orders\"]}}}"]
                     (send-request server-port "POST" "/house/http" anna-cookie "{\"orders/all\":{}}"))))
            (testing "a rate limit: two a minute per session, a third is refused; another session has its own"
              (let [counted "{\"orders/count\":0,\"mailroom/notes\":{\"orders/count\":{\"tables\":[]}}}"]
                (is (= [[200 counted] [200 counted]
                        [200 "{\"orders/count\":{\"error\":\"Too many requests.\"}}"]]
                       (repeatedly 3 #(send-request server-port "POST" "/house/http" anna-cookie "{\"orders/count\":{}}"))))
                (is (= [200 counted] (send-request server-port "POST" "/house/http" bob-cookie "{\"orders/count\":{}}")))))
            (testing "doorbell: each line hears the version first, then every changed table, never a row"
              (let [anna-line  (open-sse server-port anna-cookie)
                    bob-line   (open-sse server-port bob-cookie)
                    ask-orders #(send-request server-port "POST" "/house/http" % "{\"orders/all\":{}}")
                    orders-are #(str "{\"orders/all\":[" (str/join "," (map (fn [id] (str "\"" id "\"")) %))
                                     "],\"mailroom/notes\":{\"orders/all\":{\"tables\":[\"orders\"]}}}")]
                (is (= "{\"house/version\":\"test-1\"}" (read-next-data anna-line)))
                (is (= "{\"house/version\":\"test-1\"}" (read-next-data bob-line)))
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
                        ask-actions #(send-request server-port "POST" "/house/http" % "{\"doorman/my-actions\":{}}")
                        notes       #(str ",\"mailroom/notes\":{\"doorman/my-actions\":{\"tables\":[" % "]}}}")
                        read-tables "\"doorman/memberships\",\"doorman/role-actions\",\"doorman/scopes\""]
                    (is (= "{\"house/version\":\"test-1\"}" (read-next-data anna-line)))
                    (is (= "{\"house/version\":\"test-1\"}" (read-next-data bob-line)))
                    (is (= [200 (str "{\"doorman/my-actions\":[{\"scope-kind\":\"shop\",\"scope-id\":\"" shop-7 "\","
                                     "\"actions\":[\"orders/edit\",\"orders/see\"]}]" (notes read-tables))]
                           (ask-actions anna-cookie))
                        "Anna's shop and her role's actions; the notes name doorman's tables")
                    (is (= [200 (str "{\"doorman/my-actions\":[]" (notes ""))] (ask-actions nil))
                        "nobody signed in, and this app opens no action to anyone: none, and nothing read")
                    (Thread/sleep 200)
                    (run-sql! owner-pool (str "INSERT INTO doorman.memberships VALUES ('" anna "', 'shop', '" shop-9 "', 'Owner')"))
                    (is (= "{\"doorbell/changed\":{\"table\":\"doorman/memberships\"}}" (read-next-data anna-line))
                        "the table her my-actions read, so her screen asks again")
                    (is (= "{\"doorbell/changed\":{\"table\":\"doorman/memberships\"}}" (read-next-data bob-line)))
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
                  (is (= [200 {"orders/add" {"error" "This one-time key was used by someone else."}}]
                         (send-write server-port bob-cookie key-1 (add-order shop-9 0))))
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
                  (is (= [200 {"orders/add" {"error" "The one-time key must be a UUID."}}]
                         (send-write server-port anna-cookie "seven" (add-order shop-7 0))))
                  (is (= (+ 3 before) (count-orders))))
                (testing "a write without a key runs every time"
                  (send-write server-port anna-cookie nil (add-order shop-7 0))
                  (send-write server-port anna-cookie nil (add-order shop-7 0))
                  (is (= (+ 5 before) (count-orders))))
                (testing "a write whose answer doesn't fit its shape saves nothing"
                  (let [key-3 (random-uuid)]
                    (is (= [200 {"orders/add-badly" {"error" "Something went wrong."}}]
                           (send-write server-port anna-cookie key-3
                                       (str "{\"orders/add-badly\":{\"order/shop-id\":\"" shop-7 "\"}}"))))
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
                (testing "house's background job deletes the forgotten keys"
                  (Thread/sleep 1600)
                  (is (= 1 (kept-keys key-1)))
                  (database/run-in-transaction! owner-pool {:read-only? false}
                                                (fn [transaction]
                                                  ((:run (run-once/make-forget-keys-kind (get-in running [:settings :run-once])))
                                                   {:transaction transaction} {})))
                  (is (zero? (kept-keys key-1))))))
            (testing "one message's lines, mailroom's, cellar's and the app's own, carry its request id and the person"
              (reset! lines [])
              (is (= [200 "{\"orders/broken\":{\"error\":\"Something went wrong.\"}}"]
                     (send-request server-port "POST" "/house/http" anna-cookie "{\"orders/broken\":{}}")))
              (let [said    (first (find-lines "test/about-to-break"))
                    unknown (first (find-lines "cellar/tables-read-unknown"))
                    failed  (first (find-lines "mailroom/handler-failed"))]
                (is (some? (get-in said ["context" "request-id"])))
                (is (= [(get said "context")] (distinct (map #(get % "context") [said unknown failed])))
                    "the same context on all three: one request id")
                (is (= (str anna) (get-in failed ["context" "person-id"])) "added by doorman's session step")
                (is (= ["info" "warn" "error"] (map #(get % "level") [said unknown failed])))
                (is (= "the app's step broke" (get-in failed ["error" "message"])))))
            (testing "the errors route: only with its token; the kept errors after ?since="
              (let [ask-errors (fn [token query]
                                 (let [response (.send (HttpClient/newHttpClient)
                                                       (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/house/errors" query)))
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
                  (is (= "mailroom/handler-failed" (get (peek errors) "event")))
                  (is (= ["seq" "time" "level" "event" "context" "data" "error"] (vec (keys (peek errors))))
                      "each as written, its number first")
                  (is (= [200 "[]"] (ask-errors "the-errors-token" (str "?since=" last-seq))) "none after the last")
                  (testing "a browser's error, sent as a message, is kept with them, with the person"
                    (let [send-error #(send-request server-port "POST" "/house/http" anna-cookie
                                                    (str "{\"house/browser-error\":{\"message\":\"x is undefined\","
                                                         "\"stack\":\"at start (main.js:1)\",\"url\":\"http://app.test/orders/42\"}}"))]
                      (is (= [200 "{\"house/browser-error\":true}"] (send-error)))
                      (let [[status body] (ask-errors "the-errors-token" (str "?since=" last-seq))
                            [kept]        (json/read-value body)]
                        (is (= 200 status))
                        (is (= ["error" "browser/uncaught" (str anna)
                                {"browser/message" "x is undefined" "browser/stack" "at start (main.js:1)"
                                 "browser/url"     "http://app.test/orders/42"}]
                               [(get kept "level") (get kept "event") (get-in kept ["context" "person-id"]) (get kept "data")])))
                      (testing "its rate limit, from the :errors settings: two a minute per asker"
                        (is (= [200 "{\"house/browser-error\":true}"] (send-error)))
                        (is (= [200 "{\"house/browser-error\":{\"error\":\"Too many requests.\"}}"] (send-error))))
                      (testing "from another site: refused by doorman's session step, not written"
                        (let [before (count (find-lines "browser/uncaught"))
                              answer (.body (.send (HttpClient/newHttpClient)
                                                   (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/house/http")))
                                                       (.header "origin" "https://evil.test")
                                                       (.POST (HttpRequest$BodyPublishers/ofString
                                                               "{\"house/browser-error\":{\"message\":\"m\",\"stack\":null,\"url\":\"u\"}}"))
                                                       (.build))
                                                   (HttpResponse$BodyHandlers/ofString)))]
                          (is (str/includes? answer "error"))
                          (is (= before (count (find-lines "browser/uncaught")))))))))))
            (testing "the metrics: only with their token; every library's on the app's one meter, and house's"
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
                (doseq [pattern [#"mailroom_messages_total\{name=\"orders/all\",outcome=\"ok\",pipe=\"http\"\} [1-9]"
                                 #"mailroom_open_lines\{pipe=\"sse\"\} "
                                 #"cellar_query_seconds_count\{query=\"house/server/main/claim_once_key.sql\"\} [1-9]"
                                 #"cellar_pool_connections\{state=\"idle\"\} "
                                 #"doorbell_listening 1"
                                 #"doorbell_changes_total\{table=\"orders\"\} [1-9]"
                                 #"backroom_dispatcher_failures_total 0"
                                 #"logbook_lines_total\{level=\"info\"\} [1-9]"
                                 #"logbook_errors_kept [1-9]"
                                 #"house_build_info\{version=\"test-1\"\} 1"
                                 #"house_stage\{stage=\"started\"\} 1"
                                 #"jvm_threads_current [1-9]"]]
                  (is (re-find pattern text) (str pattern)))))
            (testing "a visitor's session: from the app's pages only; a cookie once, none for someone signed in"
              (let [start-visitor (fn [origin cookie]
                                    (let [response (.send (HttpClient/newHttpClient)
                                                          (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/house/visitor")))
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
                (is (str/starts-with? (str set-cookie) "house_session="))
                (is (= [200 "{\"visitor-id\":null}" nil] (start-visitor "http://app.test" anna-cookie))
                    "Anna is signed in: no visitor, no new cookie")))
            (testing "the REPL: house's tools in study.tools"
              (with-open [connection (nrepl/connect :port (study/find-repl-port (:study running)))]
                (let [client (nrepl/client connection 10000)
                      eval!  #(first (nrepl/response-values (nrepl/message client {:op "eval" :code %})))]
                  (let [ask-all (fn [person] (set (map str (:orders/all (eval! (str "(study.tools/ask-as-person {:orders/all {}} {:person-id #uuid \""
                                                                                                    person "\" :visitor-id nil :session-flags #{}})"))))))
                        pauls   (ask-all paul)
                        bobs    (ask-all bob)]
                    (is (and (contains? pauls (str order-71)) (not (contains? pauls (str order-91))))
                        "as Paul, shop 7's founder, through the check and the gates: only his shop's orders")
                    (is (and (contains? bobs (str order-91)) (not (contains? bobs (str order-71))))
                        "as Bob: none of shop 7's"))
                  (is (= {:handler :orders/all :answer :not-found}
                         (select-keys (eval! (str "(study.tools/why-refused {:person-id #uuid \"" (random-uuid) "\" :visitor-id nil :session-flags #{}} :orders/all {})"))
                                      [:handler :answer]))
                      "someone in no shop: doorman's explain-refusal says why")
                  (let [{:keys [shape doc]} (eval! "(study.tools/explain-setting [:server :ping-every-ms])")]
                    (is (some? shape))
                    (is (str/includes? doc "every key required") "the text of mailroom's settings shape"))
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
              (run-sql! owner-pool "ALTER ROLE house_app NOLOGIN")
              (run-sql! owner-pool "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'house_app'")
              (let [started-at        (System/currentTimeMillis)
                    [status body]     (ask-health server-port "/health/ready")
                    took-ms           (- (System/currentTimeMillis) started-at)]
                (is (= 503 status))
                (is (re-find #"\"status\":\"not-ready\",\"version\":\"test-1\",\"reason\":\"the database (did not answer within 500 ms|answered with an error)\"" body))
                (is (< took-ms 2000) "the timeout, not the pool's wait for a connection"))
              (is (= 503 (first (ask-health server-port "/health/ready"))) "a second check waits on the first")
              (is (= 200 (first (ask-health server-port "/health/live"))))
              (run-sql! owner-pool "ALTER ROLE house_app LOGIN")
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
