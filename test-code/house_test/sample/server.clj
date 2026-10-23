(ns house-test.sample.server
  "The sample app's server half, for house's end-to-end test: shops, each
   a scope, and their orders; two handlers, the orders of a shop and a new
   order; two pages, the orders of a shop and the reports, a module
   loaded on demand."
  (:require
   [cellar.api :as database]
   [doorman.api :as doorman]
   [house-test.sample.shapes :as shapes]
   [mailroom.api :as mailroom]))

(def scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops :parent nil :single? false
           :actions {:orders/see #{:read} :orders/add #{:read :insert}}
           :roles   {"Staff" #{:orders/see :orders/add}}
           :denies  []}}))

(def shops
  {:table    :shops
   :columns  [[:shop/id :uuid {:primary-key? true}]
              [:shop/name :string]]
   :indexes  []
   :access   :public
   :doorbell :tell})

(def orders
  {:table    :orders
   :columns  [[:order/id :uuid {:primary-key? true :default :random-uuid}]
              [:order/shop-id :uuid {:references :shop/id}]
              [:order/note :string]
              [:order/created-at :time/instant {:default :now}]]
   :indexes  [[:order/shop-id :order/created-at]]
   :access   [{:allow #{:orders/see :orders/add} :to [:member :order/shop-id]}]
   :doorbell :tell})

(def ^:private find-orders-of-shop
  (database/make-query-from-sql-text
   "sample: the orders of a shop"
   "SELECT order__id, order__note FROM orders WHERE order__shop_id = :shop/id ORDER BY order__created_at, order__id"))

(def ^:private insert-order
  (database/make-query-from-sql-text
   "sample: a new order"
   "INSERT INTO orders (order__shop_id, order__note) VALUES (:order/shop-id, :order/note) RETURNING order__id"))

(def handlers
  {:orders/of-shop (merge (:orders/of-shop shapes/shapes-by-name)
                          {:action       :orders/see
                           :rate-limit   nil
                           :run-steps-in nil
                           :steps        [{:name  :sample/find-orders-of-shop
                                           :enter (fn [{:mailroom/keys [message-data] :as context}]
                                                    (mailroom/add-answer-to-context
                                                     context (database/find-rows (:doorman/transaction context) find-orders-of-shop
                                                                                 (select-keys message-data [:shop/id]))))}]})
   :orders/add     (merge (:orders/add shapes/shapes-by-name)
                          {:action       :orders/add
                           :rate-limit   nil
                           :run-steps-in nil
                           :steps        [{:name  :sample/add-order
                                           :enter (fn [{:mailroom/keys [message-data] :as context}]
                                                    (mailroom/add-answer-to-context
                                                     context (database/find-one-row (:doorman/transaction context) insert-order
                                                                                    (select-keys message-data [:order/shop-id :order/note]))))}]})})

(def pages
  {:orders/page  {:address "/shops/:shop-id/orders" :title "Orders"}
   :reports/page {:address "/reports" :title "Reports" :module "reports"}})

(def browser-modules
  {:reports {:entries '[house-test.sample.reports] :depends-on #{:main}}})

(defn make-app
  "The app house's start-app! takes, with these settings."
  [settings]
  {:settings             settings
   :browser-entry        'house-test.sample.browser/start!
   :browser-modules      browser-modules
   :tables               [shops orders]
   :changes              []
   :scope-kinds          scope-kinds
   :handlers             handlers
   :pages                pages
   :background-job-kinds []
   :schedules            []
   :may-upload?          (fn [{:keys [person-id]}] (some? person-id))
   :on-delivery-event    {}
   :on-inbound-email     {}})
