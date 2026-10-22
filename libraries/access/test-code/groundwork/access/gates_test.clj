(ns groundwork.access.gates-test
  "The gates, end to end: the app's own role owns nothing, so Postgres's
   row rules hold for it. A query with no WHERE still returns only the
   asking person's rows, and a new row in another shop is refused. Runs
   when GROUNDWORK_SQL_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork.access.api :as access]
   [groundwork.schema.api :as schema]
   [groundwork.sessions.api :as sessions]
   [groundwork.sql.api :as sql]
   [groundwork.steps.api :as steps]
   [groundwork.transport.api :as transport])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "GROUNDWORK_SQL_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}}}))

(def ^:private shops {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :access :public})

(def ^:private orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid {:references :shop/id}]]
   :access  [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}]})

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")
(def ^:private order-42 #uuid "42000000-0000-4000-8000-000000000042")
(def ^:private order-99 #uuid "99000000-0000-4000-8000-000000000099")

(def ^:private settings {:session-days 1 :allowed-origin "http://app.test" :secure-cookie? false})

(def ^:private find-all-orders (sql/make-query-from-sql-text "all orders, no WHERE" "SELECT order__id FROM orders ORDER BY order__id"))
(def ^:private insert-order (sql/make-query-from-sql-text "insert order" "INSERT INTO orders (order__shop_id) VALUES (:order/shop-id)"))

(def ^:private handlers
  {:orders/all   {:action :orders/see :params-shape [:map] :answer-shape [:vector :uuid]
                  :steps  [{:name  :answer-all-orders
                            :enter #(steps/add-answer-to-context
                                     % (mapv :order/id (sql/find-rows (:groundwork.access/transaction %) find-all-orders {})))}]}
   :orders/place {:action :orders/edit :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :steps  [{:name  :insert-order
                            :enter #(do (sql/run-statement! (:groundwork.access/transaction %) insert-order
                                                            (:groundwork.transport/message-data %))
                                        (steps/add-answer-to-context % {}))}]}})

(defn- run-sql! [pool text] (sql/run-statement! pool (sql/make-query-from-sql-text "test" text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'gw_app') THEN CREATE ROLE gw_app LOGIN; END IF; END $$")
  (let [tables (into (access/put-gates-on-tables [shops orders] scope-kinds) (conj access/access-tables sessions/sessions-table))]
    (access/install-access-functions! owner-pool)        ; before the gates: their policies call these
    (with-out-str @(:slow-part (schema/migrate! owner-pool tables [])))
    (schema/grant-tables-to-role! owner-pool tables "gw_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (sql/run-in-transaction! owner-pool
    (fn [transaction]
      (access/add-scope! transaction scope-kinds :shop shop-7 paul)
      (access/add-scope! transaction scope-kinds :shop shop-9 bob)
      (access/add-membership! transaction [:shop shop-7] anna "Staff"))))

(defn- start-pool! [user]
  (sql/start-pool! {:host "localhost" :port port :database "postgres" :user user :password nil
                    :tls :off :pool-size 4 :statement-timeout-ms 5000 :time-zone "UTC"}))

(defn- ask [port cookie message-text]
  (.body (.send (HttpClient/newHttpClient)
                (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/groundwork/http")))
                  true   (.header "origin" "http://app.test")
                  cookie (.header "cookie" cookie)
                  true   (-> (.POST (HttpRequest$BodyPublishers/ofString message-text)) (.build)))
                (HttpResponse$BodyHandlers/ofString))))

(deftest a-table-that-names-a-scope-needs-access
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a scope but has no :access"
                        (access/put-gates-on-tables [shops (dissoc orders :access)] scope-kinds))))

(deftest the-gates-end-to-end
  (if-not port
    (println "groundwork.access.gates-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [owner-pool (start-pool! (System/getenv "GROUNDWORK_SQL_TEST_USER"))]
      (make-database! owner-pool)
      (let [app-pool (start-pool! "gw_app")
            ready    (access/put-access-into-handlers handlers {:pool app-pool :tables [shops orders] :scope-kinds scope-kinds
                                                                :find-session-step (sessions/make-find-session-step app-pool settings)})
            server   (transport/start-server! (get-in (first (transport/make-routes-for-messages ready {})) [1 :post :handler])
                                              {:ip "127.0.0.1" :port 0})
            cookie   #(first (str/split (:set-cookie (sessions/sign-in! owner-pool % settings)) #";"))]
        (try
          (testing "a query with no WHERE: Postgres returns only the asker's rows"
            (is (= (str "{\"orders/all\":[\"" order-42 "\"]}") (ask (:port server) (cookie anna) "{\"orders/all\":{}}")))
            (is (= (str "{\"orders/all\":[\"" order-99 "\"]}") (ask (:port server) (cookie bob) "{\"orders/all\":{}}")))
            (is (= "{\"orders/all\":[]}" (ask (:port server) nil "{\"orders/all\":{}}")) "nobody: no rows"))
          (testing "a new row in another shop is refused by the gate"
            (is (= "{\"orders/place\":{\"error\":\"Something went wrong.\"}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-9 "\"}}"))))
            (is (= "{\"orders/place\":{}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-7 "\"}}")))
                "in her own shop: fine"))
          (finally
            (transport/stop-server! server)
            (sql/stop-pool! app-pool)
            (sql/stop-pool! owner-pool)))))))
