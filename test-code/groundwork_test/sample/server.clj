(ns groundwork-test.sample.server
  "The sample app's server half, for groundwork's end-to-end test: shops, each
   a scope, and their orders; two handlers, the orders of a shop and a new
   order; two pages, the orders of a shop and the reports, a module
   loaded on demand."
  (:require
   [groundwork-postgres.api :as postgres]
   [groundwork-access.api :as access]
   [groundwork-test.sample.shapes :as shapes]
   [groundwork-steps.api :as steps]))

(def scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops :parent nil :single? false
           :actions {:orders/see #{:read} :orders/add #{:read :insert}}
           :roles   {"Staff" #{:orders/see :orders/add}}
           :denies  []}}))

(def shops
  {:table    :shops
   :columns  [[:shop/id :uuid {:primary-key? true}]
              [:shop/name :string]]
   :indexes  []
   :uniques  []
   :checks   []
   :references []
   :triggers  []
   :access   :public
   :live-updates :tell})

(def orders
  {:table    :orders
   :columns  [[:order/id :uuid {:primary-key? true :default :random-uuid}]
              [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
              [:order/note :string]
              [:order/created-at :time/instant {:default :now}]]
   :indexes  [{:columns [[:order/shop-id :ascending :nulls-last] [:order/created-at :ascending :nulls-last]]
               :method :btree :ops {} :unique? false :where nil}]
   :uniques  []
   :checks   []
   :references []
   :triggers  []
   :access   [{:allow #{:orders/see :orders/add} :only :all-of-the-action :to [:member :order/shop-id]}]
   :live-updates :tell})

(def ^:private find-orders-of-shop
  (postgres/make-query-from-sql-text
   "sample: the orders of a shop"
   "SELECT order__id, order__note FROM orders WHERE order__shop_id = param('shop/id') ORDER BY order__created_at, order__id"))

(def ^:private insert-order
  (postgres/make-query-from-sql-text
   "sample: a new order"
   "INSERT INTO orders (order__shop_id, order__note) VALUES (param('order/shop-id'), param('order/note')) RETURNING order__id"))

(def handlers
  {:orders/of-shop (merge (:orders/of-shop shapes/shapes-by-name)
                          {:action       :orders/see
                           :scope        [:shop :shop/id]          ; the shop the param names
                           :rate-limit   nil
                           :daily-caps   []
                           :run-steps-in nil
                           :steps        [{:name  :sample/find-orders-of-shop
                                           :enter (fn [{:groundwork-steps/keys [message-data] :as context}]
                                                    (steps/add-answer-to-context
                                                     context (postgres/find-rows (:groundwork-access/transaction context) find-orders-of-shop
                                                                                 (select-keys message-data [:shop/id]))))}]})
   :orders/add     (merge (:orders/add shapes/shapes-by-name)
                          {:action       :orders/add
                           :scope        [:shop :order/shop-id]
                           :rate-limit   nil
                           :daily-caps   [{:max 100 :per :asker} {:max 1000 :per :everyone}]   ; counted in Postgres
                           :run-steps-in nil
                           :steps        [{:name  :sample/add-order
                                           :enter (fn [{:groundwork-steps/keys [message-data] :as context}]
                                                    (steps/add-answer-to-context
                                                     context (postgres/find-one-row (:groundwork-access/transaction context) insert-order
                                                                                    (select-keys message-data [:order/shop-id :order/note]))))}]})})

(def pages
  ;; the orders page starts with the root; the reports page with :reports, which the
  ;; orders page loads on demand
  {:orders/page  {:address "/shops/:shop-id/orders" :title "Orders" :module :main}
   :reports/page {:address "/reports" :title "Reports" :module :reports}})

(def browser-modules
  {:main    {:entries '[groundwork-test.sample.browser] :depends-on #{} :start 'groundwork-test.sample.browser/start!}
   :reports {:entries '[groundwork-test.sample.reports] :depends-on #{:main} :start nil}})

(defn make-app
  "The app groundwork's start-app! takes, with these settings."
  [settings]
  {:settings             settings
   :browser-modules      browser-modules
   :tables               [shops orders]
   :extensions           []
   :functions            []
   :views                []
   :changes              []
   :scope-kinds          scope-kinds
   :handlers             handlers
   :pages                pages
   :not-found-module     :main
   :background-job-kinds []
   :schedules            []
   :given-to-every-background-job-run {}
   :routes               {}
   :render-page          (fn [_request page _tools]
                           (if page
                             {:answer :page :status 200 :title "Sample" :description nil :head-html "" :body-html ""}
                             {:answer :page :status 404 :title "Not found" :description nil :head-html "" :body-html "No such page."}))
   :file-kinds           []
   :may-upload?          (fn [{:keys [person-id]}] (some? person-id))
   :on-delivery-event    {}
   ;; the sample takes no inbound email: every one is ignored
   :on-inbound-email     (fn [_email _context] nil)})
