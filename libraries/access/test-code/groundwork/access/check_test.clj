(ns groundwork.access.check-test
  "The check, end to end: a session cookie, a message over http, a record
   in a shop. Runs when GROUNDWORK_SQL_TEST_PORT is set."
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
   {:shop {:table :shops :actions {:orders/see #{:read}} :roles {"Staff" #{:orders/see}}}}))

(def ^:private shops {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]})

(def ^:private orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true}]
             [:order/shop-id :uuid {:references :shop/id}]]
   :access  [{:allow :orders/see :to [:member :order/shop-id]}]})

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")
(def ^:private order-42 #uuid "42000000-0000-4000-8000-000000000042")
(def ^:private order-99 #uuid "99000000-0000-4000-8000-000000000099")

(def ^:private settings {:session-days 1 :allowed-origin "http://app.test" :secure-cookie? false})

(def ^:private answer-the-order
  {:name  :answer-the-order
   :enter #(steps/add-answer-to-context % {:order/id (get-in % [:groundwork.transport/message-data :order/id])})})

(def ^:private handlers
  {:orders/one {:action       :orders/see
                :record       [:orders :order/id]
                :params-shape [:map [:order/id :uuid]]
                :answer-shape [:map [:order/id :uuid]]
                :steps        [answer-the-order]}})

(defn- run-sql! [pool text] (sql/run-statement! pool (sql/make-query-from-sql-text "test" text) {}))

(defn- ask [port cookie order-id]
  (.body (.send (HttpClient/newHttpClient)
                (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/groundwork/http")))
                  true   (.header "origin" "http://app.test")
                  cookie (.header "cookie" cookie)
                  true   (-> (.POST (HttpRequest$BodyPublishers/ofString (str "{\"orders/one\":{\"order/id\":\"" order-id "\"}}")))
                             (.build)))
                (HttpResponse$BodyHandlers/ofString))))

(defn- make-database! [pool]
  (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork CASCADE")
  (with-out-str @(:slow-part (schema/migrate! pool (into [shops orders sessions/sessions-table] access/access-tables) [])))
  (access/install-access-functions! pool)
  (run-sql! pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (run-sql! pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (sql/run-in-transaction! pool
    (fn [transaction]
      (access/add-scope! transaction scope-kinds :shop shop-7 paul)
      (access/add-scope! transaction scope-kinds :shop shop-9 bob)
      (access/add-membership! transaction [:shop shop-7] anna "Staff"))))

(deftest a-handler-must-declare-its-action-and-shapes
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :action"
                        (access/put-access-into-handlers {:x/y {:steps []}} {:tables [shops orders] :scope-kinds scope-kinds}))))

(deftest the-check-end-to-end
  (if-not port
    (println "groundwork.access.check-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [pool   (sql/start-pool! {:host "localhost" :port port :database "postgres"
                                   :user (System/getenv "GROUNDWORK_SQL_TEST_USER") :password nil
                                   :tls :off :pool-size 4 :statement-timeout-ms 5000 :time-zone "UTC"})
          ready  (access/put-access-into-handlers handlers {:pool pool :tables [shops orders] :scope-kinds scope-kinds
                                                            :find-session-step (sessions/make-find-session-step pool settings)})
          server (transport/start-server! (get-in (first (transport/make-routes-for-messages ready {})) [1 :post :handler])
                                          {:ip "127.0.0.1" :port 0})
          cookie #(first (str/split (:set-cookie (sessions/sign-in! pool % settings)) #";"))]
      (try
        (make-database! pool)
        (let [anna-cookie (cookie anna)]
          (testing "Anna, Staff in shop 7, sees order 42"
            (is (= (str "{\"orders/one\":{\"order/id\":\"" order-42 "\"}}") (ask (:port server) anna-cookie order-42))))
          (testing "Anna asks for shop 9's order 99: not found"
            (is (= "{\"orders/one\":{\"error\":\"Not found.\"}}" (ask (:port server) anna-cookie order-99)))))
        (testing "Bob, Owner of shop 9 only, asks for order 42: not found"
          (is (= "{\"orders/one\":{\"error\":\"Not found.\"}}" (ask (:port server) (cookie bob) order-42))))
        (testing "nobody signed in: not found"
          (is (= "{\"orders/one\":{\"error\":\"Not found.\"}}" (ask (:port server) nil order-42))))
        (finally
          (transport/stop-server! server)
          (sql/stop-pool! pool))))))
