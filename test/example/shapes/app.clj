(ns example.shapes.app
  "An example app for the permission shapes: shops with a catalogue the
   public reads, a platform that owns the catalogue's shared rows, and
   orders that stay the shop's own.

   Its tables are made by `tables` (a test's fixture runs it; an app's own
   would be migrations), its permission description is `description`, its
   endpoints `endpoints`."
  (:require
   [groundwork.postgres :as pg]))

(def platform
  "The platform's own scope: the owner of the catalogue's shared rows."
  #uuid "00000000-0000-4000-8000-000000000001")

(def tables
  "DROP TABLE IF EXISTS sh_packages, sh_price_sheets, sh_agreements, sh_order_services, sh_orders;
   CREATE TABLE sh_packages (package__id uuid PRIMARY KEY, package__shop_id uuid NOT NULL,
                             package__name text NOT NULL, package__published boolean NOT NULL);
   CREATE TABLE sh_orders (order__id uuid PRIMARY KEY, order__shop_id uuid NOT NULL,
                           order__customer_id uuid, order__note text NOT NULL,
                           UNIQUE (order__id, order__shop_id));
   -- between two scopes: a shop and the partner it works with
   CREATE TABLE sh_agreements (agreement__id uuid PRIMARY KEY, agreement__shop_id uuid NOT NULL,
                               agreement__partner_id uuid NOT NULL,
                               UNIQUE (agreement__id, agreement__shop_id, agreement__partner_id));
   -- under it: both scopes are the agreement's, tied by the matching link
   CREATE TABLE sh_price_sheets (price_sheet__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                 price_sheet__agreement_id uuid NOT NULL,
                                 price_sheet__shop_id uuid NOT NULL, price_sheet__partner_id uuid NOT NULL,
                                 price_sheet__cents bigint NOT NULL,
                                 FOREIGN KEY (price_sheet__agreement_id, price_sheet__shop_id, price_sheet__partner_id)
                                   REFERENCES sh_agreements (agreement__id, agreement__shop_id, agreement__partner_id)
                                   ON UPDATE CASCADE);
   -- under an order: the order's shop, and a partner of its own, which may wait empty
   CREATE TABLE sh_order_services (order_service__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                   order_service__order_id uuid NOT NULL,
                                   order_service__shop_id uuid NOT NULL, order_service__partner_id uuid,
                                   order_service__name text NOT NULL,
                                   FOREIGN KEY (order_service__order_id, order_service__shop_id)
                                     REFERENCES sh_orders (order__id, order__shop_id) ON UPDATE CASCADE);")

(def drop-tables
  "DROP TABLE IF EXISTS sh_packages, sh_price_sheets, sh_agreements, sh_order_services, sh_orders")

(def description
  {:scopes  {:shop {:actions #{:catalogue/see :catalogue/edit :shop/card :orders/see :orders/edit
                               :prices/see :prices/edit :services/see}
                    :roles   {"Staff" #{:catalogue/edit :orders/see :orders/edit :prices/see :prices/edit
                                        :services/see}}
                    :parent  nil}}
   :records {:package {:table :sh_packages :id :package/id :in [:shop :package/shop-id]
                       ;; the public reads a shop's published packages, and nothing else of the shop
                       :open      #{:catalogue/see :shop/card}
                       :only-when {:catalogue/see [:= :package/published true]
                                   :shop/card     [:= :package/published true]}}
             :order   {:table :sh_orders :id :order/id :in [:shop :order/shop-id]
                       :people {:orders/see :order/customer-id}}
             ;; a record of two scopes, and the records under it
             :agreement   {:table :sh_agreements :id :agreement/id
                           :in [[:shop :agreement/shop-id] [:shop :agreement/partner-id]]}
             :price-sheet {:table :sh_price_sheets :id :price-sheet/id
                           :in [[:shop :price-sheet/shop-id] [:shop :price-sheet/partner-id]]
                           :parent [:agreement :price-sheet/agreement-id
                                    {:price-sheet/shop-id    :agreement/shop-id
                                     :price-sheet/partner-id :agreement/partner-id}]}
             ;; one scope from its parent, one of its own
             :order-service {:table :sh_order_services :id :order-service/id
                             :in [[:shop :order-service/shop-id] [:shop :order-service/partner-id]]
                             :parent [:order :order-service/order-id {:order-service/shop-id :order/shop-id}]}}
   :links   []
   :open    {:anyone #{:catalogue/see} :signed-in #{:shop/card}}
   :session-blocks {}})

(pg/defsql packages-of-shop)
(pg/defsql package)
(pg/defsql rename-package)
(pg/defsql orders-of-shop)
(pg/defsql count-orders)

(pg/defsql prices-of-agreement)
(pg/defsql add-price)
(pg/defsql services)
(pg/defsql add-service)
(pg/defsql order-note)

(def package-shape [:map [:package/id :uuid] [:package/name :string]])

(def endpoints
  {;; open to anyone: a shop's published packages
   :catalogue/of-shop {:read       (fn [{:keys [db]} params] (pg/rows db packages-of-shop params))
                       :permission {:action :catalogue/see :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     [:vector package-shape]}
   :catalogue/package {:read       (fn [{:keys [db]} params] (pg/row db package params))
                       :permission {:action :catalogue/see :record [:package :package/id]}
                       :input      [:map [:package/id :uuid]]
                       :answer     [:maybe package-shape]}
   ;; a shop's catalogue as its page shows it: its own published packages and
   ;; the shared ones the platform owns, in one read
   :catalogue/for-shop {:read       (fn [{:keys [db]} params] (pg/rows db packages-of-shop params))
                        :permission {:action :catalogue/see :scope [:shop :shop/id] :and-scope [:shop platform]}
                        :input      [:map [:shop/id :uuid]]
                        :answer     [:vector package-shape]}
   ;; the same for an action people hold: both scopes must verify
   :catalogue/workbench {:read       (fn [{:keys [db]} params] (pg/rows db packages-of-shop params))
                         :permission {:action :catalogue/edit :scope [:shop :shop/id] :and-scope [:shop platform]}
                         :input      [:map [:shop/id :uuid]]
                         :answer     [:vector package-shape]}
   ;; the same open action, with SQL that reaches for the shop's orders: the
   ;; wall shows a stranger none
   :catalogue/busy?   {:read       (fn [{:keys [db]} params] (:orders/count (pg/row db count-orders params)))
                       :permission {:action :catalogue/see :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     :int}
   ;; open to anyone signed in
   :shop/card         {:read       (fn [{:keys [db]} params] (count (pg/rows db packages-of-shop params)))
                       :permission {:action :shop/card :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     :int}
   ;; members only
   :catalogue/rename  {:write      (fn [{:keys [db]} params] (pg/row db rename-package params))
                       :permission {:action :catalogue/edit :record [:package :package/id]}
                       :input      [:map [:package/id :uuid] [:package/name :string]]
                       :answer     [:maybe package-shape]}
   ;; a record of two scopes and what hangs under it: either side reads and writes
   :prices/of-agreement {:read       (fn [{:keys [db]} params] (mapv :price-sheet/cents (pg/rows db prices-of-agreement params)))
                         :permission {:action :prices/see :record [:agreement :agreement/id]}
                         :input      [:map [:agreement/id :uuid]]
                         :answer     [:vector :int]}
   ;; the SQL names no scope: Groundwork fills both from the agreement
   :prices/add          {:write      (fn [{:keys [db]} params] (:price-sheet/id (pg/row db add-price params)))
                         :permission {:action :prices/edit :record [:agreement :agreement/id]}
                         :input      [:map [:agreement/id :uuid] [:price-sheet/cents :int]]
                         :answer     :uuid}
   ;; a service on an order: the order's shop sees it, and the partner it names
   :services/mine       {:read       (fn [{:keys [db]} _] (mapv :order-service/name (pg/rows db services {})))
                         :permission {:action :services/see :list :order-service}
                         :input      [:map]
                         :answer     [:vector :string]}
   :services/add        {:write      (fn [{:keys [db]} params] (:order-service/id (pg/row db add-service params)))
                         :permission {:action :orders/edit :record [:order :order/id]}
                         :input      [:map [:order/id :uuid] [:order-service/name :string]
                                      ;; a scope's id, not a record's
                                      [:order-service/partner-id {:groundwork/record false} [:maybe :uuid]]]
                         :answer     :uuid}
   :orders/note         {:read       (fn [{:keys [db]} params] (:order/note (pg/row db order-note params)))
                         :permission {:action :orders/see :record [:order :order/id]}
                         :input      [:map [:order/id :uuid]]
                         :answer     [:maybe :string]}
   :orders/of-shop    {:read       (fn [{:keys [db]} params] (mapv :order/id (pg/rows db orders-of-shop params)))
                       :permission {:action :orders/see :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     [:vector :uuid]}})
