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
   ready, a database that doesn't answer or lets nobody in, and stopping.
   Runs when
   HOUSE_SQL_TEST_PORT is set."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as database]
   [doorbell.api :as doorbell]
   [doorman.api :as doorman]
   [house.server.main.api :as main]
   [jsonista.core :as json]
   [mailroom.api :as mailroom])
  (:import
   (java.io BufferedReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "HOUSE_SQL_TEST_PORT") parse-long))
(def ^:private owner (System/getenv "HOUSE_SQL_TEST_USER"))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}}}))

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
    (spit (io/file folder "js" "manifest.edn") (pr-str [{:module-id :main :output-name "main.0A1B2C3D4E5F.js"}]))
    (spit (io/file folder "js" "main.0A1B2C3D4E5F.js") "start();")
    (with-open [out (java.util.zip.GZIPOutputStream. (io/output-stream (io/file folder "js" "main.0A1B2C3D4E5F.js.gz")))]
      (.write out (.getBytes "start();")))
    (io/make-parents (io/file folder "css" "x"))
    (spit (io/file folder "css" "manifest.edn") (pr-str [{:output-name "main.9F8E7D6C5B4A.css"}]))
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
   :time-zone "UTC"})

(defn- make-settings [user]
  {:version           "test-1"
   :wire              {:most-shapes-kept 1000
                       :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                                          :max-name-length 50000 :max-document-length nil :max-token-count nil
                                          :max-writing-nesting-depth 1000}}
   :browser-wire      {:most-shapes-kept 1000 :most-keys-kept 10000}
   :logbook           {:lowest-level :debug :format :json :errors-kept 100
                       :write-to     #(swap! lines conj (json/read-value %))}
   :errors            {:path "/house/errors" :token "the-errors-token" :browser-rate-limit {:max 2 :per-ms 60000}}
   :migrate-at-start? false
   :browser-build     :built
   :server            {:ip "127.0.0.1" :port 0 :proxy-protocol :disable :client-address-header nil :server-header nil
                       :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
                       :addresses {:http "/house/http" :sse "/house/sse" :ws "/house/ws"}
                       :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
                       :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
                       :rate-limit-buckets-before-cleanup 10000}
   :database          (make-database-settings user)
   :migration         {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}
   :sessions          {:cookie-name "house_session" :session-days 1 :renew-after-minutes 60
                       :allowed-origin "http://app.test" :secure-cookie? false}
   :my-actions        {:rate-limit {:max 60 :per-ms 60000}}
   :background-jobs   {:workers 1 :poll-ms 1000
                       :retry-first-wait-ms 30000 :retry-wait-factor 2 :retry-max-wait-ms 3600000
                       :stuck-after-time-limit-ms 60000 :forget-done-after-ms 604800000 :stop-wait-ms 5000}
   :doorbell          {:store     {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
                       :listening {:batch-window-ms 50 :check-stopped-every-ms 500
                                   :reconnect-after-ms 1000 :stop-wait-ms 2000}}
   :page              {:title "Shops <test>" :language "en" :viewport "width=device-width, initial-scale=1"
                       :content-security-policy :none :referrer-policy "same-origin" :frame-options "DENY"
                       :files {:folder files-folder :hashed-name-min-hex-digits 8 :hashed-file-cache-seconds 31536000}}
   :health            {:live-path "/health/live" :ready-path "/health/ready"
                       :database-timeout-ms 500 :not-ready-before-stop-ms 1000}})

(defn- make-app [user]
  {:settings (make-settings user) :tables [shops orders] :changes [] :scope-kinds scope-kinds
   :handlers handlers :pages {:orders/page {:address "/orders/:order-id" :title "Order"}}
   :browser-entry 'app.browser.main/start!
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
  (is (thrown? clojure.lang.ExceptionInfo
               (main/check-settings (assoc (make-settings "x") :browser-build {:watch {:entry 'app.browser.main/start!}})))
      "the watch options leave out the entry and the folder: they come from the app and :page")
  (is (thrown? clojure.lang.ExceptionInfo
               (main/check-settings (assoc (make-settings "x") :browser-build {:watch {:log (fn [_ _ _])}})))
      "and the log: house passes its own"))

(deftest the-whole-server
  (if-not port
    (println "house.server.main.api-test: skipped, HOUSE_SQL_TEST_PORT is not set")
    (let [owner-pool (database/start-pool! (make-database-settings owner))]
      (try
        (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE; DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS doorman CASCADE")
        (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'house_app') THEN CREATE ROLE house_app LOGIN; END IF; END $$")
        (testing "an app's handler named :doorman/my-actions is refused: it is house's"
          (is (= :house.server.main/handler-name-taken
                 (try (main/start-app! (-> (make-app owner)
                                           (assoc-in [:settings :migrate-at-start?] true)
                                           (assoc-in [:handlers :doorman/my-actions] (:orders/count handlers))))
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE; DROP SCHEMA IF EXISTS backroom CASCADE; DROP SCHEMA IF EXISTS doorman CASCADE")
        (testing "an owner role, or a database not migrated, is refused"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owns tables or passes the gates"
                                (main/start-app! (make-app owner)))))
        (with-out-str (main/migrate-app! (make-app owner) (make-database-settings owner) "house_app"))
        (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
        (database/run-in-transaction! owner-pool {:read-only? false}
                                      (fn [transaction]
                                        (doorman/add-scope! transaction scope-kinds :shop shop-7 paul)
                                        (doorman/add-scope! transaction scope-kinds :shop shop-9 bob)
                                        (doorman/add-membership! transaction [:shop shop-7] anna "Staff")))
        (let [running     (main/start-app! (make-app "house_app"))
              stopped?    (atom false)
              server-port (get-in running [:server :port])
              cookie      #(first (str/split (:set-cookie (doorman/sign-in! (:pool running) % (get-in running [:settings :sessions]))) #";"))
              anna-cookie (cookie anna)
              bob-cookie  (cookie bob)]
          (try
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
                (is (str/includes? html "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"))
                (is (str/includes? html "\"orders/page\":{\"address\":\"/orders/:order-id\",\"title\":\"Order\"}"))
                (is (str/includes? html "\"sse\":\"/house/sse\""))
                (is (str/includes? html "<script src=\"/js/main.0A1B2C3D4E5F.js\" defer></script>")
                    "the script the build's manifest lists")
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
                        read-tables "\"doorman/memberships\",\"doorman/role-actions\""]
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
