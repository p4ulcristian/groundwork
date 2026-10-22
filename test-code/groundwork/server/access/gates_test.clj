(ns groundwork.server.access.gates-test
  "The gates, end to end: the app's own role owns nothing, so Postgres's
   row rules hold for it. A query with no WHERE still returns only the
   asking person's rows, and a new row in another shop is refused. Runs
   when GROUNDWORK_SQL_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork.server.access.api :as access]
   [groundwork.server.sessions.api :as sessions]
   [groundwork.server.database.api :as database]
   [groundwork.server.steps.api :as steps]
   [groundwork.server.transport.api :as transport-server])
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

(def ^:private find-all-orders (database/make-query-from-sql-text "all orders, no WHERE" "SELECT order__id FROM orders ORDER BY order__id"))
(def ^:private insert-order (database/make-query-from-sql-text "insert order" "INSERT INTO orders (order__shop_id) VALUES (:order/shop-id)"))

(def ^:private handlers
  {:orders/all   {:action :orders/see :params-shape [:map] :answer-shape [:vector :uuid]
                  :steps  [{:name  :answer-all-orders
                            :enter #(steps/add-answer-to-context
                                     % (mapv :order/id (database/find-rows (:groundwork.server.access/transaction %) find-all-orders {})))}]}
   :orders/sneak {:action :orders/see :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :steps  [{:name  :insert-order-while-only-seeing
                            :enter #(do (database/run-statement! (:groundwork.server.access/transaction %) insert-order
                                                                 (:groundwork.server.transport/message-data %))
                                        (steps/add-answer-to-context % {}))}]}
   :orders/place {:action :orders/edit :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :steps  [{:name  :insert-order
                            :enter #(do (database/run-statement! (:groundwork.server.access/transaction %) insert-order
                                                            (:groundwork.server.transport/message-data %))
                                        (steps/add-answer-to-context % {}))}]}})

(defn- run-sql! [pool text] (database/run-statement! pool (database/make-query-from-sql-text "test" text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'gw_app') THEN CREATE ROLE gw_app LOGIN; END IF; END $$")
  (let [tables (into (access/put-gates-on-tables [shops orders] scope-kinds) (conj access/access-tables sessions/sessions-table))]
    (access/install-access-functions! owner-pool)        ; before the gates: their policies call these
    (with-out-str @(:slow-part (database/migrate! owner-pool tables [])))
    (database/grant-tables-to-role! owner-pool tables "gw_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (database/run-in-transaction! owner-pool
    (fn [transaction]
      (access/add-scope! transaction scope-kinds :shop shop-7 paul)
      (access/add-scope! transaction scope-kinds :shop shop-9 bob)
      (access/add-membership! transaction [:shop shop-7] anna "Staff"))))

(defn- start-pool! [user]
  (database/start-pool! {:host "localhost" :port port :database "postgres" :user user :password nil
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
    (println "groundwork.server.access.gates-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [owner-pool (start-pool! (System/getenv "GROUNDWORK_SQL_TEST_USER"))]
      (make-database! owner-pool)
      (let [app-pool (start-pool! "gw_app")
            ready    (access/put-access-into-handlers handlers {:pool app-pool :tables [shops orders] :scope-kinds scope-kinds
                                                                :find-session-step (sessions/make-find-session-step app-pool settings)})
            server   (transport-server/start-server! (get-in (first (transport-server/make-routes-for-messages ready {})) [1 :post :handler])
                                              {:ip "127.0.0.1" :port 0})
            cookie   #(first (str/split (:set-cookie (sessions/sign-in! owner-pool % settings)) #";"))]
        (try
          (testing "a query with no WHERE: Postgres returns only the asker's rows"
            (is (= (str "{\"orders/all\":[\"" order-42 "\"]}") (ask (:port server) (cookie anna) "{\"orders/all\":{}}")))
            (is (= (str "{\"orders/all\":[\"" order-99 "\"]}") (ask (:port server) (cookie bob) "{\"orders/all\":{}}")))
            (is (= "{\"orders/all\":{\"error\":\"Not found.\"}}" (ask (:port server) nil "{\"orders/all\":{}}"))
                "nobody can hold orders/see: the list doesn't run at all"))
          (testing "a new row in another shop is refused by the gate"
            (is (= "{\"orders/place\":{\"error\":\"Something went wrong.\"}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-9 "\"}}"))))
            (is (= "{\"orders/place\":{}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-7 "\"}}")))
                "in her own shop: fine"))
          (testing "an action that only reads runs read-only: a write in its steps is refused"
            (is (= "{\"orders/sneak\":{\"error\":\"Something went wrong.\"}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/sneak\":{\"order/shop-id\":\"" shop-7 "\"}}")))
                "Anna may edit shop 7's orders, but orders/see only reads"))
          (testing "the leak test: Anna, asked for shop 9's order, gets nothing of it"
            (let [anna-request {:request-method :post
                                :headers {"origin" "http://app.test" "cookie" (cookie anna)}}]
              (is (= [] (access/find-leaks (select-keys ready [:orders/all]) anna-request {:orders [order-99]})))
              (testing "and a handler that reads past the gates is caught"
                (let [leaky {:orders/leaky {:action :orders/see :params-shape [:map] :answer-shape [:vector :uuid]
                                            :steps [{:name  :read-with-the-owner
                                                     :enter #(steps/add-answer-to-context
                                                              % (mapv :order/id (database/find-rows owner-pool find-all-orders {})))}]}}
                      ready-leaky (access/put-access-into-handlers leaky {:pool app-pool :tables [shops orders] :scope-kinds scope-kinds
                                                                          :find-session-step (sessions/make-find-session-step app-pool settings)})]
                  (is (= [{:handler :orders/leaky :gave-back [(str order-99)]}]
                         (access/find-leaks ready-leaky anna-request {:orders [order-99]})))))))
          (finally
            (transport-server/stop-server! server)
            (database/stop-pool! app-pool)
            (database/stop-pool! owner-pool)))))))
