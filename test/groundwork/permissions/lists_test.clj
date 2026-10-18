(ns groundwork.permissions.lists-test
  "Lists get their permission from the wall: a list's SQL has no WHERE, and
   still sees only rows a sentence gives the person. A via gives the rows
   its sentence is on and nothing else of that shop; a :when holds on what
   a write may change; a customer sees their own order and no other."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.endpoints :as endpoints]
   [groundwork.permissions.leaks :as leaks]
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
  {:scopes  {:shop    {:actions {:orders/see #{:read} :orders/edit #{:read :update} :invoices/see #{:read}}
                       :roles   {"Production" #{:orders/see :orders/edit :invoices/see}}
                       :parent  nil}
             :partner {:actions {:agreements/see #{:read}} :roles {"Member" #{:agreements/see}} :parent nil}}
   :records {:order     {:table :lst_orders :id :lst-order/id
                         :whose {:lst-order/shop-id :shop :lst-order/customer-id :person}}
             :invoice   {:table :lst_invoices :id :lst-invoice/id :whose {:lst-invoice/shop-id :shop}}
             :agreement {:table :lst_agreements :id :lst-agreement/id
                         :whose {:lst-agreement/shop-id :shop :lst-agreement/partner-id :partner}}}
   :rules   [{:allow :orders/see :on :order :to [:member :lst-order/shop-id]}
             {:allow :orders/see :on :order :to [:me :lst-order/customer-id]}
             {:allow :orders/edit :on :order :to [:member :lst-order/shop-id] :when [:= :lst-order/status "open"]}
             {:allow :orders/see :on :order
              :via   {:table :lst_agreements :match {:lst-order/shop-id :lst-agreement/shop-id}
                      :to [:member :lst-agreement/partner-id :agreements/see] :when link-condition}}
             {:allow :invoices/see :on :invoice :to [:member :lst-invoice/shop-id]}]})

(def sealed (description [:= :lst-agreement/status "active"]))
(def broken (description [:not-null :lst-agreement/status]))

(pg/defsql ls-all-orders)
(pg/defsql ls-all-invoices)
(pg/defsql ls-close-order)
(pg/defsql ls-editable-orders)

(defn- ids [rows] (into #{} (map (comp first vals)) rows))

(def endpoint-map
  {:orders/all          {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-orders))))
                         :access     {:action :orders/see :list :order}
                         :input      [:map]
                         :answer     [:vector :uuid]}
   :orders/editable     {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-orders))))
                         :access     {:action :orders/edit :list :order}
                         :input      [:map]
                         :answer     [:vector :uuid]}
   ;; the same list, narrowed in its SQL to the orders the person may edit
   :orders/editable-only {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-editable-orders))))
                          :access     {:action :orders/see :list :order}
                          :input      [:map]
                          :answer     [:vector :uuid]}
   :orders/and-invoices {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-invoices))))
                         :access     {:action :orders/see :list :order}
                         :input      [:map]
                         :answer     [:vector :uuid]}
   :orders/one          {:read       (fn [{:keys [db]} _] (vec (ids (pg/rows db ls-all-orders))))
                         :access     {:action :orders/see :record [:order :lst-order/id]}
                         :input      [:map [:lst-order/id :uuid]]
                         :answer     [:vector :uuid]}
   :orders/close        {:write      (fn [{:keys [db]} params] (first (ids [(pg/row db ls-close-order params)])))
                         :access     {:action :orders/edit :record [:order :lst-order/id]}
                         :input      [:map [:lst-order/id :uuid]]
                         :answer     [:maybe :uuid]}})

(def settings {:list [endpoint-map] :max-asks 50 :max-running 4 :bearer nil :on-answer nil
               :bells {:global {} :personal {} :silent {:groundwork/every-other-table "the test database is shared by every test"}}})

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
           CREATE INDEX ON lst_orders (lst_order__shop_id);
           CREATE TABLE lst_invoices (lst_invoice__id uuid PRIMARY KEY, lst_invoice__shop_id uuid NOT NULL);
           CREATE TABLE lst_agreements (lst_agreement__partner_id uuid NOT NULL, lst_agreement__shop_id uuid NOT NULL,
                                        lst_agreement__status text NOT NULL,
                                        lst_agreement__id uuid PRIMARY KEY DEFAULT gen_random_uuid());
           CREATE INDEX ON lst_agreements (lst_agreement__shop_id);
           CREATE INDEX ON lst_agreements (lst_agreement__partner_id)")
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

(deftest a-when-holds-on-writes-and-the-list-filter
  (let [p (part sealed)]
    (testing "the wall knows reading, not actions: a list for :orders/edit shows what Peter reads"
      (is (= #{acme-open acme-closed} (ask p :orders/editable peter))))
    (testing "a query file narrows it with the function made for the action, :when included"
      (is (= #{acme-open} (ask p :orders/editable-only peter)))
      (is (= #{} (ask p :orders/editable-only pia)) "the partner sees orders, may edit none")
      (is (= #{} (ask p :orders/editable-only kati)) "nor may the customer")
      (testing "it runs behind the wall: in a transaction not verified for Acme, nothing"
        (is (= #{} (perm/in-wall (:perms p) {:person peter :scopes #{}}
                                 (fn [tx] (ids (jdbc/execute! (::pg/conn tx) ["SELECT * FROM groundwork.\"may orders/edit on order\"()"]))))))))
    (testing "the list filter narrows to the action, :when included"
      (let [{:keys [sql params]} (perm/list-filter (:perms p) {:person peter :flags #{} :action :orders/edit :kind :order})]
        (is (= #{acme-open}
               (perm/as-system (:perms p)
                               (fn [tx] (ids (jdbc/execute! (::pg/conn tx)
                                                            (into [(str "SELECT r.lst_order__id FROM lst_orders r WHERE " sql)]
                                                                  params)))))))))
    (testing "a closed order isn't editable: the endpoint says not-found, and the wall touches nothing"
      (is (= :not-found (ask p :orders/close peter {:lst-order/id acme-closed})))
      (is (= 0 (perm/in-wall (:perms p) {:person peter :scopes #{acme}}
                             (fn [tx] (:next.jdbc/update-count
                                       (first (jdbc/execute! (::pg/conn tx)
                                                             ["UPDATE lst_orders SET lst_order__customer_id = NULL WHERE lst_order__id = ?"
                                                              acme-closed]))))))))
    (testing "closing an open order still works: the changed row needn't fit :when"
      (let [order (id)]
        (try
          (perm/as-system (:perms p)
                          (fn [tx] (jdbc/execute! (::pg/conn tx) ["INSERT INTO lst_orders VALUES (?, ?, NULL, 'open')" order acme])))
          (is (= #{order} (ask p :orders/close peter {:lst-order/id order})))
          (is (= :not-found (ask p :orders/close peter {:lst-order/id order})) "closed now, so no longer editable")
          (finally
            (perm/as-system (:perms p)
                            (fn [tx] (jdbc/execute! (::pg/conn tx) ["DELETE FROM lst_orders WHERE lst_order__id = ?" order])))))))))

(deftest a-via-gives-its-kind-and-nothing-else
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
        (is (= [] (:hidden report)))
        (is (some #{:orders/all :orders/editable-only} (:checked report)))))
    (testing "the functions that narrow by an action answer what the sentences give, behind the wall"
      (is (= [] (leaks/may-check (:perms (part sealed)) {:shop zinc} {:person peter})))
      (is (= [] (leaks/may-check (:perms (part sealed)) {:shop acme} {:person pia}))))
    (testing "a via that forgot its status: Zinc's orders show up in the partner's list"
      (let [report (leaks/check (part broken) {:person pia :foreign foreign})]
        (is (contains? (set (map :endpoint (:leaked report))) :orders/all))))))
