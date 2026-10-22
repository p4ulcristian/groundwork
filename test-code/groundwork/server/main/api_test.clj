(ns groundwork.server.main.api-test
  "The whole server, started as production starts it: migrate-app! as the
   owner, then start-app! as a role that owns nothing. The page shell, a
   message with its notes, and sync: a new order reaches the sse line of
   the shop's member and not another shop's owner. Runs when
   GROUNDWORK_SQL_TEST_PORT is set."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork.server.access.api :as access]
   [groundwork.server.database.api :as database]
   [groundwork.server.main.api :as main]
   [groundwork.server.sessions.api :as sessions]
   [groundwork.server.steps.api :as steps])
  (:import
   (java.io BufferedReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "GROUNDWORK_SQL_TEST_PORT") parse-long))
(def ^:private owner (System/getenv "GROUNDWORK_SQL_TEST_USER"))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :access :public :sync :everyone})

(def ^:private orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid {:references :shop/id}]]
   :access  [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}]
   :sync    :by-access})

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")
(def ^:private order-71 #uuid "71000000-0000-4000-8000-000000000071")
(def ^:private order-91 #uuid "91000000-0000-4000-8000-000000000091")

(def ^:private find-all-orders
  (database/make-query-from-sql-text "all orders, no WHERE" "SELECT order__id FROM orders ORDER BY order__id"))

(def ^:private handlers
  {:orders/all {:action :orders/see :params-shape [:map] :answer-shape [:vector :uuid]
                :steps  [{:name  :answer-all-orders
                          :enter #(steps/add-answer-to-context
                                   % (mapv :order/id (database/find-rows (:groundwork.server.access/transaction %) find-all-orders {})))}]}})

(def ^:private files-folder
  "A release build's folder, as the deploy leaves it: a manifest and one hashed file."
  (let [folder (.toFile (java.nio.file.Files/createTempDirectory "groundwork-files" (make-array java.nio.file.attribute.FileAttribute 0)))]
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
   :tls :off :pool-size 4 :statement-timeout-ms 5000 :time-zone "UTC"})

(defn- make-settings [user]
  {:version           "test-1"
   :migrate-at-start? false
   :browser-build     :built
   :server            {:ip "127.0.0.1" :port 0}
   :database          (make-database-settings user)
   :sessions          {:session-days 1 :allowed-origin "http://app.test" :secure-cookie? false}
   :background-jobs   {:workers 1 :poll-ms 1000}
   :page              {:title "Shops <test>" :language "en"
                       :content-security-policy :none :files-folder files-folder}})

(defn- make-app [user]
  {:settings (make-settings user) :tables [shops orders] :changes [] :scope-kinds scope-kinds
   :handlers handlers :pages {:orders/page {:address "/orders/:order-id" :title "Order"}}
   :browser-entry 'app.browser.main/start!
   :background-job-kinds [] :schedules []})

(defn- run-sql! [pool text] (database/run-statement! pool (database/make-query-from-sql-text "test" text) {}))

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

(deftest the-settings-are-checked-all-at-once
  (let [problems (try (main/check-settings (-> (make-settings "x") (dissoc :version) (assoc-in [:server :port] "80")))
                      (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
    (is (contains? problems :version))
    (is (contains? (:server problems) :port))))

(deftest the-whole-server
  (if-not port
    (println "groundwork.server.main.api-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [owner-pool (database/start-pool! (make-database-settings owner))]
      (try
        (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork CASCADE")
        (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'gw_app') THEN CREATE ROLE gw_app LOGIN; END IF; END $$")
        (testing "an owner role, or a database not migrated, is refused"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owns tables or passes the gates"
                                (main/start-app! (make-app owner)))))
        (with-out-str (main/migrate-app! (make-app owner) (make-database-settings owner) "gw_app"))
        (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
        (database/run-in-transaction! owner-pool
          (fn [transaction]
            (access/add-scope! transaction scope-kinds :shop shop-7 paul)
            (access/add-scope! transaction scope-kinds :shop shop-9 bob)
            (access/add-membership! transaction [:shop shop-7] anna "Staff")))
        (let [running     (main/start-app! (make-app "gw_app"))
              server-port (get-in running [:server :port])
              cookie      #(first (str/split (:set-cookie (sessions/sign-in! (:pool running) % (get-in running [:settings :sessions]))) #";"))
              anna-cookie (cookie anna)
              bob-cookie  (cookie bob)]
          (try
            (testing "a page opened fresh: the shell, with the browser's settings in it"
              (let [[status html] (send-request server-port "GET" "/orders/42" nil nil)]
                (is (= 200 status))
                (is (str/includes? html "<title>Shops &lt;test&gt;</title>"))
                (is (str/includes? html "\"version\":\"test-1\""))
                (is (str/includes? html "\"orders/page\":{\"address\":\"/orders/:order-id\",\"title\":\"Order\"}"))
                (is (str/includes? html "\"sse\":\"/groundwork/sse\""))
                (is (str/includes? html "<script src=\"/js/main.0A1B2C3D4E5F.js\" defer></script>")
                    "the script the build's manifest lists")
                (is (str/includes? html "<link rel=\"stylesheet\" href=\"/css/main.9F8E7D6C5B4A.css\">")
                    "the stylesheet bundle its manifest lists")))
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
              (is (= [200 "{\"orders/all\":[],\"groundwork/notes\":{\"orders/all\":{\"tables\":[\"orders\"]}}}"]
                     (send-request server-port "POST" "/groundwork/http" anna-cookie "{\"orders/all\":{}}"))))
            (testing "sync: each line hears the version first, then the changes its person may read"
              (let [anna-line (open-sse server-port anna-cookie)
                    bob-line  (open-sse server-port bob-cookie)]
                (is (= "{\"groundwork/version\":\"test-1\"}" (read-next-data anna-line)))
                (is (= "{\"groundwork/version\":\"test-1\"}" (read-next-data bob-line)))
                (Thread/sleep 200)
                (is (= 2 (count (keys @(get-in running [:sync :lines])))))
                (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-71 "', '" shop-7 "')"))
                (is (= (str "{\"sync/changed\":{\"table\":\"orders\",\"ids\":[\"" order-71 "\"]}}") (read-next-data anna-line)))
                (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-91 "', '" shop-9 "')"))
                (is (= (str "{\"sync/changed\":{\"table\":\"orders\",\"ids\":[\"" order-91 "\"]}}") (read-next-data bob-line))
                    "Bob's next message is his own shop's order: shop 7's never reached him")
                (testing "a :sync :everyone table tells every line"
                  (run-sql! owner-pool "INSERT INTO shops VALUES ('10000000-0000-4000-8000-000000000001')")
                  (is (= "{\"sync/changed\":{\"table\":\"shops\",\"ids\":[\"10000000-0000-4000-8000-000000000001\"]}}" (read-next-data anna-line)))
                  (is (= "{\"sync/changed\":{\"table\":\"shops\",\"ids\":[\"10000000-0000-4000-8000-000000000001\"]}}" (read-next-data bob-line))))
                (testing "a statement that changed no row tells nobody"
                  (run-sql! owner-pool "UPDATE orders SET order__shop_id = order__shop_id WHERE false")
                  (is (= :nothing (read-next-data anna-line))))
                ;; not closed here: a read that timed out still holds them;
                ;; stopping the server ends both streams
                ))
            (finally (main/stop-app! running))))
        (finally (database/stop-pool! owner-pool))))))
