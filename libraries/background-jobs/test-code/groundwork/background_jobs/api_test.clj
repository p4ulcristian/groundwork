(ns groundwork.background-jobs.api-test
  "Runs against a real Postgres when GROUNDWORK_SQL_TEST_PORT is set (on
   localhost, user from GROUNDWORK_SQL_TEST_USER, no password, no TLS);
   says it was skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.background-jobs.api :as background-jobs]
   [groundwork.sql.api :as sql]))

(def ^:private port (some-> (System/getenv "GROUNDWORK_SQL_TEST_PORT") parse-long))

(def ^:private ran (atom []))

(def ^:private say-hello
  {:name         :test/say-hello
   :params-shape [:map [:order/id :int]]
   :run          (fn [_job-context params] (swap! ran conj (:order/id params)))
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

(deftest jobs-against-postgres
  (if-not port
    (println "groundwork.background-jobs.api-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [pool (sql/start-pool! {:host "localhost" :port port :database "postgres"
                                 :user (System/getenv "GROUNDWORK_SQL_TEST_USER") :password nil
                                 :tls :off :pool-size 4 :statement-timeout-ms 5000 :time-zone "UTC"})]
      (try
        (background-jobs/create-tables! pool)
        (let [workers (background-jobs/start-workers! pool {:kinds [say-hello give-up] :schedules []
                                                            :workers 2 :poll-ms 100})]
          (try
            (testing "a queued job runs, with its params as shaped data"
              (background-jobs/add-job-to-queue! pool say-hello {:order/id 42})
              (wait-until #(seq @ran))
              (is (= [42] @ran)))
            (testing "params that don't fit the shape are refused"
              (is (thrown? clojure.lang.ExceptionInfo
                           (background-jobs/add-job-to-queue! pool say-hello {:order/id "42"}))))
            (testing "a job that gives up is dead at once, with its message"
              (let [id (background-jobs/add-job-to-queue! pool give-up {})]
                (wait-until #(some (fn [job] (= id (:job/id job))) (background-jobs/list-dead-jobs pool 10)))
                (is (= "clojure.lang.ExceptionInfo: The order is gone"
                       (:job/last-error (first (filter #(= id (:job/id %)) (background-jobs/list-dead-jobs pool 10))))))))
            (testing "one job per key"
              (is (= (background-jobs/add-job-to-queue! pool say-hello {:order/id 1} {:key "k" :run-in-ms 60000})
                     (background-jobs/add-job-to-queue! pool say-hello {:order/id 1} {:key "k" :run-in-ms 60000}))))
            (finally (background-jobs/stop-workers! workers))))
        (finally (sql/stop-pool! pool))))))
