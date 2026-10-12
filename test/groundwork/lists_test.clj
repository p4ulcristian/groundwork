(ns groundwork.lists-test
  "Lists get their permission from the wall: a list's SQL has no WHERE, and
   still sees only what its action's rule allows. A link gives the linked
   action's records and nothing else of that shop; an only-when holds on a
   list; a customer sees their own order and no other."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.endpoints :as endpoints]
   [groundwork.leaks :as leaks]
   [groundwork.migrations :as migrations]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [db-settings sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.util UUID)))

(defn- id [] (UUID/randomUUID))

(def acme (id)) (def zinc (id)) (def partner (id))
(def paul (id)) (def peter (id)) (def ana (id)) (def pat (id)) (def pia (id)) (def kati (id))
(def acme-open (id)) (def acme-closed (id)) (def zinc-open (id)) (def zinc-katis (id))
(def acme-invoice (id)) (def zinc-invoice (id))

(defn- description [link-condition]
  {:scopes  {:shop    {:actions #{:orders/see :orders/edit :invoices/see}
                       :roles   {"Production" #{:orders/see :orders/edit :invoices/see}}
                       :parent  nil}
             :partner {:actions #{:agreements/see} :roles {"Member" #{:agreements/see}} :parent nil}}
   :records {:order   {:table :lst_orders :id :lst-order/id :in [:shop :lst-order/shop-id]
                       :people {:orders/see :lst-order/customer-id}
                       :only-when {:orders/edit [:= :lst-order/status "open"]}}
             :invoice {:table :lst_invoices :id :lst-invoice/id :in [:shop :lst-invoice/shop-id]}}
   :links   [{:table  :lst_agreements
              :from   [:partner :lst-agreement/partner-id]
              :to     [:shop :lst-agreement/shop-id]
              :when   link-condition
              :grants {:orders/see :agreements/see}}]
   :session-blocks {}})

(def sealed (description [:= :lst-agreement/status "active"]))
(def broken (description [:not-null :lst-agreement/status]))

(pg/defsql ls-all-orders)
(pg/defsql ls-all-invoices)
(pg/defsql ls-close-order)

(defn- ids [rows] (into #{} (map (comp first vals)) rows))

(def endpoint-map
  {:orders/all          {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-orders))))
                         :permission {:action :orders/see :list :order}
                         :input      [:map]
                         :answer     [:vector :uuid]}
   :orders/editable     {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-orders))))
                         :permission {:action :orders/edit :list :order}
                         :input      [:map]
                         :answer     [:vector :uuid]}
   :orders/and-invoices {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-invoices))))
                         :permission {:action :orders/see :list :order}
                         :input      [:map]
                         :answer     [:vector :uuid]}
   :orders/one          {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-orders))))
                         :permission {:action :orders/see :record [:order :lst-order/id]}
                         :input      [:map [:lst-order/id :uuid]]
                         :answer     [:vector :uuid]}
   :orders/close        {:write      (fn [{:keys [db]} params] (first (ids [(pg/row db ls-close-order params)])))
                         :permission {:action :orders/edit :record [:order :lst-order/id]}
                         :input      [:map [:lst-order/id :uuid]]
                         :answer     [:maybe :uuid]}})

(def settings {:endpoints [endpoint-map] :max-asks 50 :max-running 4 :bearer nil :on-answer nil
               :bells {:global #{} :personal {}}})

(def ^:dynamic *db* nil)

(defn- part [d]
  (endpoints/start {:settings settings :db *db* :permissions (perm/start {:settings d :db *db*})
                    :app-settings {}}))

(defn- join! [perms by scope person role]
  (let [email (str person "@test.example")
        token (perm/invite! perms {:by by :scope scope :email email :role role :days 7})]
    (perm/accept-invitation! perms {:token token :person person :email email})))

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS lst_orders, lst_invoices, lst_agreements;
           CREATE TABLE lst_orders (lst_order__id uuid PRIMARY KEY, lst_order__shop_id uuid NOT NULL,
                                    lst_order__customer_id uuid, lst_order__status text NOT NULL);
           CREATE TABLE lst_invoices (lst_invoice__id uuid PRIMARY KEY, lst_invoice__shop_id uuid NOT NULL);
           CREATE TABLE lst_agreements (lst_agreement__partner_id uuid NOT NULL, lst_agreement__shop_id uuid NOT NULL,
                                        lst_agreement__status text NOT NULL)")
    (sql! (str "INSERT INTO lst_orders VALUES (?, ?, NULL, 'open'), (?, ?, NULL, 'closed'),"
               "                              (?, ?, NULL, 'open'), (?, ?, ?, 'closed');"
               "INSERT INTO lst_invoices VALUES (?, ?), (?, ?);"
               "INSERT INTO lst_agreements VALUES (?, ?, 'active'), (?, ?, 'ended')")
          acme-open acme acme-closed acme zinc-open zinc zinc-katis zinc kati
          acme-invoice acme zinc-invoice zinc
          partner acme partner zinc)
    (let [db (pg/start db-settings)]
      (try
        (migrations/migrate! db)
        (sql! "TRUNCATE groundwork.scopes, groundwork.roles, groundwork.role_actions, groundwork.memberships,
                        groundwork.extra_actions, groundwork.invitations CASCADE")
        (let [perms (perm/start {:settings sealed :db db})]
          (perm/create-scope! perms {:kind :shop :id acme :parent nil :owner paul})
          (perm/create-scope! perms {:kind :shop :id zinc :parent nil :owner ana})
          (perm/create-scope! perms {:kind :partner :id partner :parent nil :owner pat})
          (join! perms paul acme peter "Production")
          (join! perms pat partner pia "Member"))
        (binding [*db* db] (t))
        (finally
          (pg/stop db)
          (sql! "DROP TABLE IF EXISTS lst_orders, lst_invoices, lst_agreements"))))))

(defn- ask [p endpoint person & [params]]
  (let [{:keys [status data]} (endpoints/call p {:endpoint endpoint :params (or params {})
                                                 :person person :flags #{}})]
    (if (= :ok status) (set (if (coll? data) data [data])) status)))

(deftest a-list-sees-what-its-rule-allows
  (let [p (part sealed)]
    (testing "a member: their shop's orders"
      (is (= #{acme-open acme-closed} (ask p :orders/all peter))))
    (testing "through an active link: that shop's orders, not the one whose link ended"
      (is (= #{acme-open acme-closed} (ask p :orders/all pia))))
    (testing "a customer: their own order, nothing else of that shop"
      (is (= #{zinc-katis} (ask p :orders/all kati))))))

(deftest an-only-when-holds-on-a-list
  (let [p (part sealed)]
    (is (= #{acme-open} (ask p :orders/editable peter)) "only the open order is editable")
    (testing "closing an open order still works: the new row needn't fit only-when"
      (let [order (id)]
        (try
          (perm/as-system (:perms p)
                          (fn [tx] (jdbc/execute! (::pg/conn tx) ["INSERT INTO lst_orders VALUES (?, ?, NULL, 'open')" order acme])))
          (is (= #{order} (ask p :orders/close peter {:lst-order/id order})))
          (is (= #{acme-open} (ask p :orders/editable peter)) "closed now, so no longer editable")
          (finally
            (perm/as-system (:perms p)
                            (fn [tx] (jdbc/execute! (::pg/conn tx) ["DELETE FROM lst_orders WHERE lst_order__id = ?" order])))))))))

(deftest a-link-gives-the-linked-action-and-nothing-else
  (let [p (part sealed)]
    (is (= #{acme-invoice} (ask p :orders/and-invoices peter)) "a member sees the shop's invoices")
    (is (= #{} (ask p :orders/and-invoices pia)) "the partner, through the link: orders only, no invoices")))

(deftest a-customer-asking-for-their-order-sees-no-other
  (let [p (part sealed)]
    (is (= #{zinc-katis} (ask p :orders/one kati {:lst-order/id zinc-katis}))
        "the record's shop was verified, yet the wall shows her only her own order")
    (is (= :not-found (ask p :orders/one kati {:lst-order/id zinc-open})))))

(deftest the-leak-test-covers-lists
  (let [foreign {:shop zinc :order zinc-open :invoice zinc-invoice}]
    (testing "sealed: the lists hold nothing of Zinc"
      (let [report (leaks/check (part sealed) {:person pia :foreign foreign})]
        (is (= [] (:leaked report)))
        (is (some #{:orders/all} (:checked report)))))
    (testing "a link that forgot its status: Zinc's orders show up in the partner's list"
      (let [report (leaks/check (part broken) {:person pia :foreign foreign})]
        (is (contains? (set (map :endpoint (:leaked report))) :orders/all))))))
