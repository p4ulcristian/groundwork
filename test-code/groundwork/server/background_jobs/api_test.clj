(ns groundwork.server.background-jobs.api-test
  "Runs against a real Postgres when GROUNDWORK_SQL_TEST_PORT is set (on
   localhost, user from GROUNDWORK_SQL_TEST_USER, no password, no TLS);
   says it was skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.server.background-jobs.api :as background-jobs]
   [groundwork.server.database.api :as database]))

(def ^:private port (some-> (System/getenv "GROUNDWORK_SQL_TEST_PORT") parse-long))

(defn- run-sql! [pool text]
  (database/run-statement! pool (database/make-query-from-sql-text "test" text) {}))

(def ^:private ran (atom []))

(def ^:private say-hello
  {:name         :test/say-hello
   :params-shape [:map [:order/id :int]]
   :run          (fn [_background-job-context params] (swap! ran conj (:order/id params)))
   :timeout-ms   5000
   :attempts     3})

(def ^:private give-up
  {:name         :test/give-up
   :params-shape [:map]
   :run          (fn [_ _] (throw (background-jobs/make-give-up-exception "The order is gone")))
   :timeout-ms   5000
   :attempts     3})

(defn- wait-until [done?]
  (loop [n 0]
    (when (and (not (done?)) (< n 100))
      (Thread/sleep 50)
      (recur (inc n)))))

(deftest background-jobs-against-postgres
  (if-not port
    (println "groundwork.server.background-jobs.api-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [pool (database/start-pool! {:host "localhost" :port port :database "postgres"
                                 :user (System/getenv "GROUNDWORK_SQL_TEST_USER") :password nil
                                 :tls :off :pool-size 4 :statement-timeout-ms 5000 :time-zone "UTC"})]
      (try
        (with-out-str (run-sql! pool "DROP SCHEMA IF EXISTS groundwork CASCADE")
                      @(:slow-part (database/migrate! pool background-jobs/background-jobs-tables [])))
        (let [workers (background-jobs/start-workers! pool {:kinds [say-hello give-up] :schedules []
                                                            :workers 2 :poll-ms 100})]
          (try
            (testing "a queued background job runs, with its params as shaped data"
              (background-jobs/add-background-job-to-queue! pool say-hello {:order/id 42})
              (wait-until #(seq @ran))
              (is (= [42] @ran)))
            (testing "params that don't fit the shape are refused"
              (is (thrown? clojure.lang.ExceptionInfo
                           (background-jobs/add-background-job-to-queue! pool say-hello {:order/id "42"}))))
            (testing "a background job that gives up is dead at once, with its message"
              (let [id (background-jobs/add-background-job-to-queue! pool give-up {})]
                (wait-until #(some (fn [background-job] (= id (:background-job/id background-job))) (background-jobs/list-dead-background-jobs pool 10)))
                (is (= "clojure.lang.ExceptionInfo: The order is gone"
                       (:background-job/last-error (first (filter #(= id (:background-job/id %)) (background-jobs/list-dead-background-jobs pool 10))))))))
            (testing "one background job per key"
              (is (= (background-jobs/add-background-job-to-queue! pool say-hello {:order/id 1} {:key "k" :run-in-ms 60000})
                     (background-jobs/add-background-job-to-queue! pool say-hello {:order/id 1} {:key "k" :run-in-ms 60000}))))
            (finally (background-jobs/stop-workers! workers))))
        (finally (database/stop-pool! pool))))))
