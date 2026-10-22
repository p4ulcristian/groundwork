(ns groundwork.server.sync.tables-test
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.sync.api :as sync]
   [groundwork.server.sync.telling :as telling]))

(def ^:private shops {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :sync :tell})

(deftest every-table-says-how-it-syncs
  (let [problems (try (sync/check-sync-of-tables [(dissoc shops :sync)
                                                  {:table :logs :sync [:silent ""]}
                                                  {:table :odd :sync :everyone}])
                      (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= 3 (count problems)) "every problem at once")
    (is (re-find #":shops says nothing" (first problems)))
    (is (re-find #"without saying why" (second problems)))
    (is (re-find #"neither :tell nor" (nth problems 2)) "the old values are gone")))

(deftest a-table-that-tells-gets-its-triggers
  (let [[shops-ready logs-ready] (sync/put-sync-on-tables [shops {:table :logs :sync [:silent "nobody reads logs live"]}])]
    (is (= {:function "groundwork.tell_sync" :arguments []} (:on-change shops-ready))
        "no arguments: the trigger tells only the table")
    (is (nil? (:on-change logs-ready)))))

(deftest a-batch-tells-each-changed-table-once
  (let [tables (telling/list-changed-tables
                ["{\"folder\":\"public\",\"table\":\"orders\"}"
                 "{\"folder\":\"groundwork\",\"table\":\"sessions\"}"
                 "{\"folder\":\"public\",\"table\":\"orders\"}"])]
    (is (= [:orders :groundwork/sessions] tables))
    (is (= {:sync/changed {:table "orders"}} (telling/make-message (first tables))))
    (is (= {:sync/changed {:table "groundwork/sessions"}} (telling/make-message (second tables))))))
