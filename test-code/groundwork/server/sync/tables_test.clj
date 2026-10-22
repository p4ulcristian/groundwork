(ns groundwork.server.sync.tables-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.server.access.api :as access]
   [groundwork.server.sync.api :as sync]
   [groundwork.server.sync.telling :as telling]))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}} :roles {}}}))

(def ^:private shops {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :access :public :sync :everyone})

(def ^:private orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true}]
             [:order/shop-id :uuid {:references :shop/id}]
             [:order/customer-id :uuid]]
   :access  [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}
             {:allow :orders/see :to [:me :order/customer-id]}]
   :sync    :by-access})

(deftest every-table-says-how-it-syncs
  (let [problems (try (sync/check-sync-of-tables [(dissoc shops :sync)
                                                  (dissoc orders :access)
                                                  {:table :logs :sync [:silent ""]}
                                                  {:table :odd :sync :sometimes}])
                      (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= 4 (count problems)) "every problem at once")
    (is (re-find #":shops says nothing" (first problems)))
    (is (re-find #"no :access" (second problems)))))

(deftest a-table-that-tells-gets-its-triggers
  (let [[shops-ready orders-ready] (sync/put-sync-on-tables [shops orders] scope-kinds)]
    (is (= {:function "groundwork.tell_sync" :arguments ["shop__id"]} (:on-change shops-ready))
        ":everyone: only the id")
    (is (= {:function "groundwork.tell_sync" :arguments ["order__id" "order__shop_id" "order__customer_id"]}
           (:on-change orders-ready))
        ":by-access: the id and the columns the readers name"))
  (is (nil? (:on-change (first (sync/put-sync-on-tables [{:table :logs :sync [:silent "nobody reads logs live"]}] scope-kinds))))))

(deftest changes-of-one-batch-are-joined-by-table
  (let [changes (telling/merge-changes
                 [(telling/turn-payload-into-change "{\"folder\":\"public\",\"table\":\"orders\",\"ids\":[\"a\"],\"columns\":{\"order__shop_id\":[\"7\"]}}")
                  (telling/turn-payload-into-change "{\"folder\":\"public\",\"table\":\"orders\",\"ids\":[\"b\"],\"columns\":{\"order__shop_id\":[\"9\"]}}")])]
    (is (= [{:table :orders :ids #{"a" "b"} :columns {"order__shop_id" #{"7" "9"}} :all? false}] changes))
    (is (= {:sync/changed {:table "orders" :ids ["a" "b"]}} (telling/make-message (first changes))))
    (is (= {:sync/changed {:table "orders"}}
           (telling/make-message (first (telling/merge-changes
                                         [(telling/turn-payload-into-change "{\"folder\":\"public\",\"table\":\"orders\",\"all\":true}")]))))
        "too many rows to list: only the table")))
