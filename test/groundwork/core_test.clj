(ns groundwork.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.core :as gw]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [app-settings]]))

(pg/defsql timeout-setting)

(def settings app-settings)

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest settings-are-checked
  (testing "a missing setting throws, naming every one that is missing"
    (is (= {:type ::gw/missing-settings :missing [:environment :db :migrations :sessions :page :routes :server]}
           (error #(gw/start {})))))
  (testing "an unknown setting throws, so a typo can't go unnoticed"
    (is (= {:type ::gw/unknown-settings :unknown [:dbb]}
           (error #(gw/start (assoc settings :dbb {}))))))
  (testing "the environment is one of three"
    (is (= ::gw/bad-setting (:type (error #(gw/start (assoc settings :environment :staging))))))))

(deftest start-and-stop
  (let [app (gw/start settings)]
    (try
      (testing "the db part is a working db"
        (is (= {:statement-timeout/value "5s"} (pg/row (gw/db app) timeout-setting))))
      (finally (gw/stop app)))))

(deftest a-part-can-be-replaced-by-a-fake
  (let [app (gw/start-with settings {:groundwork/server ::fake-server})]
    (is (= ::fake-server (gw/part app :groundwork/server)))
    (testing "a fake isn't stopped"
      (gw/stop app))
    (testing "only parts can be replaced"
      (is (= ::gw/unknown-part (:type (error #(gw/start-with settings {:groundwork/nope 1}))))))))
