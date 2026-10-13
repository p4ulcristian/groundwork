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
  "DROP TABLE IF EXISTS sh_packages, sh_orders;
   CREATE TABLE sh_packages (package__id uuid PRIMARY KEY, package__shop_id uuid NOT NULL,
                             package__name text NOT NULL, package__published boolean NOT NULL);
   CREATE TABLE sh_orders (order__id uuid PRIMARY KEY, order__shop_id uuid NOT NULL,
                           order__customer_id uuid, order__note text NOT NULL,
                           UNIQUE (order__id, order__shop_id));")

(def drop-tables "DROP TABLE IF EXISTS sh_packages, sh_orders")

(def description
  {:scopes  {:shop {:actions #{:catalogue/see :catalogue/edit :shop/card :orders/see}
                    :roles   {"Staff" #{:catalogue/edit :orders/see}}
                    :parent  nil}}
   :records {:package {:table :sh_packages :id :package/id :in [:shop :package/shop-id]
                       ;; the public reads a shop's published packages, and nothing else of the shop
                       :open      #{:catalogue/see :shop/card}
                       :only-when {:catalogue/see [:= :package/published true]
                                   :shop/card     [:= :package/published true]}}
             :order   {:table :sh_orders :id :order/id :in [:shop :order/shop-id]
                       :people {:orders/see :order/customer-id}}}
   :links   []
   :open    {:anyone #{:catalogue/see} :signed-in #{:shop/card}}
   :session-blocks {}})

(pg/defsql packages-of-shop)
(pg/defsql package)
(pg/defsql rename-package)
(pg/defsql orders-of-shop)
(pg/defsql count-orders)

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
   :orders/of-shop    {:read       (fn [{:keys [db]} params] (mapv :order/id (pg/rows db orders-of-shop params)))
                       :permission {:action :orders/see :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     [:vector :uuid]}})
