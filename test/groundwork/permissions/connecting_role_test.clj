(ns groundwork.permissions.connecting-role-test
  "The role the app connects as must not pass by row-level security, or the
   wall would hold nothing back."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.permissions.install :as install]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [db-settings]]))

(deftest a-role-that-bypasses-rls-is-refused
  (is (re-find #"passes by row-level security"
               (install/role-problem {:role/name "neondb_owner" :role/bypasses-rls true}))))

(deftest a-plain-role-is-accepted
  (is (nil? (install/role-problem {:role/name "app" :role/bypasses-rls false}))))

(deftest the-test-role-is-a-plain-one
  ;; what the start asks the database, answered for the role the tests use
  (let [db (pg/start db-settings)]
    (try
      (is (nil? (install/role-problem (pg/row db @#'install/connecting-role {}))))
      (finally (pg/stop db)))))
