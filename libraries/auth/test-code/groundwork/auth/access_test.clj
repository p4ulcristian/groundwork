(ns groundwork.auth.access-test
  "The Postgres tests run when GROUNDWORK_SQL_TEST_PORT is set; they say
   they were skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.auth.api :as auth]
   [groundwork.database.api :as database]))

(def ^:private port (some-> (System/getenv "GROUNDWORK_SQL_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (auth/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}}}))

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private dora #uuid "00000000-0000-4000-8000-00000000000c")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")

(deftest the-scope-kinds-are-checked
  (testing "a role naming an action its kind doesn't have"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names :orders/fly"
                          (auth/check-scope-kinds {:shop {:actions {:orders/see #{:read}} :roles {"Pilot" #{:orders/fly}}}}))))
  (testing "Owner is Groundwork's own"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Owner"
                          (auth/check-scope-kinds {:shop {:actions {:orders/see #{:read}} :roles {"Owner" #{}}}})))))

(deftest who-belongs-where
  (if-not port
    (println "groundwork.auth.access-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [pool (database/start-pool! {:host "localhost" :port port :database "postgres"
                                 :user (System/getenv "GROUNDWORK_SQL_TEST_USER") :password nil
                                 :tls :off :pool-size 2 :statement-timeout-ms 5000 :time-zone "UTC"})]
      (try
        (database/run-statement! pool (database/make-query-from-sql-text "test" "DROP SCHEMA IF EXISTS groundwork CASCADE") {})
        (with-out-str @(:slow-part (database/migrate! pool auth/access-tables [])))
        (database/run-in-transaction! pool
          (fn [transaction]
            (auth/add-scope! transaction scope-kinds :shop shop-7 paul)
            (auth/add-membership! transaction [:shop shop-7] anna "Staff")
            (auth/add-membership! transaction [:shop shop-7] dora "Bookkeeper")))
        (testing "each person's actions, by scope"
          (is (= {[:shop shop-7] #{:orders/see :orders/edit}} (auth/list-actions-of-person pool scope-kinds paul)) "the founder is Owner: everything")
          (is (= {[:shop shop-7] #{:orders/see :orders/edit}} (auth/list-actions-of-person pool scope-kinds anna)))
          (is (= {[:shop shop-7] #{:orders/see}} (auth/list-actions-of-person pool scope-kinds dora)))
          (is (= {} (auth/list-actions-of-person pool scope-kinds bob)) "Bob is in no scope"))
        (testing "a shop makes a role of its own, live"
          (auth/add-role! pool scope-kinds [:shop shop-7] "Driver" #{:orders/see})
          (auth/add-membership! pool [:shop shop-7] bob "Driver")
          (is (= {[:shop shop-7] #{:orders/see}} (auth/list-actions-of-person pool scope-kinds bob))))
        (testing "a role the scope doesn't have, or an action its kind doesn't have, is refused"
          (is (thrown? clojure.lang.ExceptionInfo (auth/add-membership! pool [:shop shop-7] bob "Pilot")))
          (is (thrown? clojure.lang.ExceptionInfo (auth/add-role! pool scope-kinds [:shop shop-7] "Pilot" #{:orders/fly}))))
        (testing "leaving a scope"
          (is (true? (auth/remove-membership! pool [:shop shop-7] dora)))
          (is (= {} (auth/list-actions-of-person pool scope-kinds dora))))
        (finally (database/stop-pool! pool))))))
