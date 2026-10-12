(ns groundwork.permissions-test
  "Shops under a company, a partner linked to shops by agreements, records
   that reach their shop through other records, customers on their orders."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.migrations :as migrations]
   [groundwork.permissions :as perm]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [db-settings sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.util UUID)))

(defn- id [] (UUID/randomUUID))

;; places
(def company (id)) (def acme (id)) (def zinc (id)) (def partner (id))
;; people
(def carl (id)) (def dora (id)) (def paul (id)) (def peter (id)) (def mila (id))
(def ana (id)) (def pat (id)) (def pia (id)) (def kati (id))
;; records
(def acme-order (id)) (def zinc-order (id)) (def acme-job (id)) (def zinc-job (id))
(def draft-invoice (id)) (def sent-invoice (id)) (def acme-machine (id)) (def zinc-machine (id))
(def acme-agreement (id)) (def zinc-agreement (id))

(def description
  {:scopes  {:company {:actions #{:company/report}
                       :roles   {"Director" #{:company/report :orders/see}}
                       :parent  nil}
             :shop    {:actions #{:orders/see :orders/edit :jobs/move :invoices/edit :money/move :machines/use}
                       :roles   {"Manager"    #{:orders/see :orders/edit :jobs/move :invoices/edit
                                                :machines/use :groundwork/team}
                                 "Production" #{:orders/see :jobs/move :machines/use}}
                       :parent  :company}
             :partner {:actions #{:agreements/see}
                       :roles   {"Member" #{:agreements/see}}
                       :parent  nil}}
   :records {:order     {:table :orders :id :order/id :in [:shop :order/shop-id]
                         :people {:orders/see :order/customer-id}}
             :job       {:table :jobs :id :job/id :in [:order :job/order-id]}
             :invoice   {:table :invoices :id :invoice/id :in [:shop :invoice/shop-id]
                         :only-when {:invoices/edit [:= :invoice/status "draft"]}}
             :machine   {:table :machines :id :machine/id :in [:shop :machine/shop-id]}
             :agreement {:table :agreements :id :agreement/id
                         :in [[:shop :agreement/shop-id] [:partner :agreement/partner-id]]}}
   :links   [{:table  :agreements
              :from   [:partner :agreement/partner-id]
              :to     [:shop :agreement/shop-id]
              :when   [:= :agreement/status "active"]
              :grants {:orders/see :agreements/see}}]
   :session-blocks {:impersonated #{:money/move}}})

(def tables
  "DROP TABLE IF EXISTS jobs, orders, invoices, machines, agreements;
   CREATE TABLE orders (order__id uuid PRIMARY KEY, order__shop_id uuid NOT NULL,
                        order__customer_id uuid, order__status text NOT NULL);
   CREATE TABLE jobs (job__id uuid PRIMARY KEY, job__order_id uuid NOT NULL REFERENCES orders);
   CREATE TABLE invoices (invoice__id uuid PRIMARY KEY, invoice__shop_id uuid NOT NULL,
                          invoice__status text NOT NULL);
   CREATE TABLE machines (machine__id uuid PRIMARY KEY, machine__shop_id uuid NOT NULL);
   CREATE TABLE agreements (agreement__id uuid PRIMARY KEY, agreement__shop_id uuid NOT NULL,
                            agreement__partner_id uuid NOT NULL, agreement__status text NOT NULL);")

(def ^:dynamic *perms* nil)

(defn- join!
  "Invite a person into a scope with a role and have them accept."
  [perms by scope person role]
  (let [email (str person "@test.example")
        token (perm/invite! perms {:by by :scope scope :email email :role role :days 7})]
    (perm/accept-invitation! perms {:token token :person person :email email})))

(use-fixtures :once
  (fn [t]
    (sql! tables)
    (let [db (pg/start db-settings)]
      (try
        (migrations/migrate! db)
        (sql! "TRUNCATE groundwork.scopes, groundwork.roles, groundwork.role_actions, groundwork.memberships,
                        groundwork.extra_actions, groundwork.invitations CASCADE")
        (sql! (str "INSERT INTO orders VALUES (?, ?, ?, 'open'), (?, ?, NULL, 'open');"
                   "INSERT INTO jobs VALUES (?, ?), (?, ?);"
                   "INSERT INTO invoices VALUES (?, ?, 'draft'), (?, ?, 'sent');"
                   "INSERT INTO machines VALUES (?, ?), (?, ?);"
                   "INSERT INTO agreements VALUES (?, ?, ?, 'active'), (?, ?, ?, 'ended');")
              acme-order acme kati zinc-order zinc
              acme-job acme-order zinc-job zinc-order
              draft-invoice acme sent-invoice acme
              acme-machine acme zinc-machine zinc
              acme-agreement acme partner zinc-agreement zinc partner)
        (let [perms (perm/start {:settings description :db db})]
          (perm/create-scope! perms {:kind :company :id company :parent nil :owner carl})
          (perm/create-scope! perms {:kind :shop :id acme :parent company :owner paul})
          (perm/create-scope! perms {:kind :shop :id zinc :parent nil :owner ana})
          (perm/create-scope! perms {:kind :partner :id partner :parent nil :owner pat})
          (join! perms carl company dora "Director")
          (join! perms paul acme peter "Production")
          (join! perms paul acme mila "Manager")
          (join! perms pat partner pia "Member")
          (binding [*perms* perms] (t)))
        (finally
          (pg/stop db)
          (sql! "DROP TABLE IF EXISTS jobs, orders, invoices, machines, agreements"))))))

(defn- may? [person action record & [flags]]
  (perm/allowed? *perms* {:person person :action action :record record :flags (or flags #{})}))

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-record-is-checked-through-its-own-scope
  (testing "a job reaches its shop through its order"
    (is (may? peter :jobs/move [:job acme-job]))
    (is (not (may? peter :jobs/move [:job zinc-job])) "another shop's job"))
  (testing "a role gives only its own actions"
    (is (not (may? peter :orders/edit [:order acme-order])))
    (is (may? mila :orders/edit [:order acme-order])))
  (testing "an owner holds everything"
    (is (may? paul :money/move [:invoice draft-invoice]))
    (is (not (may? ana :money/move [:invoice draft-invoice])) "but only in their own shop"))
  (testing "nobody signed in may do nothing"
    (is (not (may? nil :orders/see [:order acme-order])))))

(deftest a-parent-scope-counts-in-its-children
  (is (may? dora :orders/see [:order acme-order]) "Director of the company that owns Acme")
  (is (not (may? dora :orders/see [:order zinc-order])) "Zinc isn't the company's")
  (is (may? carl :money/move [:invoice draft-invoice]) "the company's owner owns its shops too"))

(deftest a-link-gives-access-while-its-condition-holds
  (is (may? pia :orders/see [:order acme-order]) "Acme's agreement with the partner is active")
  (is (not (may? pia :orders/see [:order zinc-order])) "Zinc's agreement ended")
  (testing "the agreement ending ends the access, on the next check"
    (sql! "SET groundwork.bypass = 'on'; UPDATE agreements SET agreement__status = 'ended' WHERE agreement__id = ?"
          acme-agreement)
    (try
      (is (not (may? pia :orders/see [:order acme-order])))
      (finally
        (sql! "SET groundwork.bypass = 'on'; UPDATE agreements SET agreement__status = 'active' WHERE agreement__id = ?"
              acme-agreement)))))

(deftest a-shared-record-belongs-to-both-scopes
  (is (may? pia :agreements/see [:agreement acme-agreement]) "through the partner's side")
  (is (= #{acme partner} (perm/scopes-of *perms* [:agreement acme-agreement]))))

(deftest a-person-on-the-record
  (is (may? kati :orders/see [:order acme-order]) "Kati's own order")
  (is (not (may? kati :orders/see [:order zinc-order])))
  (is (not (may? kati :orders/edit [:order acme-order])) "seeing isn't editing"))

(deftest a-condition-on-the-record-narrows
  (is (may? mila :invoices/edit [:invoice draft-invoice]))
  (is (not (may? mila :invoices/edit [:invoice sent-invoice])) "only while draft")
  (is (not (may? paul :invoices/edit [:invoice sent-invoice])) "not even for the owner"))

(deftest a-session-flag-blocks
  (is (may? paul :money/move [:invoice draft-invoice]))
  (is (not (may? paul :money/move [:invoice draft-invoice] #{:impersonated}))))

(deftest every-id-in-a-request-is-checked
  (is (= #{acme} (perm/check! *perms* {:person peter :flags #{} :action :jobs/move :record [:job acme-job]
                                       :also {:machine [acme-machine]}})))
  (is (= {:type ::perm/not-found :record [:machine zinc-machine]}
         (error #(perm/check! *perms* {:person peter :flags #{} :action :jobs/move :record [:job acme-job]
                                       :also {:machine [zinc-machine]}})))
      "a machine of another shop")
  (is (= {:type ::perm/not-found :record [:job zinc-job]}
         (error #(perm/check! *perms* {:person peter :flags #{} :action :jobs/move :record [:job zinc-job]})))))

(defn- listed [person action kind table id-col]
  (let [{:keys [sql params]} (perm/list-filter *perms* {:person person :flags #{} :action action :kind kind})]
    (perm/as-system *perms*
                    (fn [tx]
                      (set (map (comp first vals)
                                (jdbc/execute! (::pg/conn tx)
                                               (into [(str "SELECT r." id-col " FROM " table " r WHERE " sql)]
                                                     params))))))))

(deftest one-rule-filters-a-list
  (is (= #{acme-order} (listed peter :orders/see :order "orders" "order__id")))
  (is (= #{acme-order} (listed pia :orders/see :order "orders" "order__id")) "through the active agreement")
  (is (= #{acme-order} (listed kati :orders/see :order "orders" "order__id")) "her own")
  (is (= #{acme-job} (listed peter :jobs/move :job "jobs" "job__id")))
  (is (= #{} (listed peter :orders/edit :order "orders" "order__id")))
  (testing "a blocked action lists nothing"
    (is (= {:sql "FALSE" :params []}
           (perm/list-filter *perms* {:person paul :flags #{:impersonated} :action :money/move :kind :invoice})))))

(defn- order-ids [tx]
  (set (map :orders/order__id (jdbc/execute! (::pg/conn tx) ["SELECT order__id FROM orders"]))))

(deftest the-database-wall
  (testing "a transaction sees only the verified scopes"
    (is (= #{acme-order} (perm/in-wall *perms* {:person peter :scopes #{acme}} order-ids)))
    (is (= #{} (perm/in-wall *perms* {:person peter :scopes #{}} order-ids))))
  (testing "a record that belongs to the person"
    (is (= #{acme-order} (perm/in-wall *perms* {:person kati :scopes #{}} order-ids))))
  (testing "a child table follows its parent's scope"
    (is (= #{acme-job} (perm/in-wall *perms* {:person peter :scopes #{acme}}
                                     (fn [tx] (set (map :jobs/job__id (jdbc/execute! (::pg/conn tx) ["SELECT job__id FROM jobs"]))))))))
  (testing "outside the wall, nothing: it fails closed"
    (is (empty? (sql! "SELECT * FROM orders"))))
  (testing "the system sees everything"
    (is (= #{acme-order zinc-order} (perm/as-system *perms* order-ids))))
  (testing "a write outside the verified scope is refused"
    (is (thrown? Exception
                 (perm/in-wall *perms* {:person peter :scopes #{acme}}
                               (fn [tx] (jdbc/execute! (::pg/conn tx)
                                                       ["INSERT INTO machines VALUES (?, ?)" (id) zinc])))))))

(deftest the-bypass-never-leaks
  (let [db    (pg/start (assoc db-settings :pool-size 1))
        perms (assoc *perms* :db db)]
    (try
      (is (= 2 (count (perm/as-system perms order-ids))))
      (testing "the next transaction on the same connection sees nothing"
        (is (= #{} (pg/with-transaction [tx db] (order-ids tx)))))
      (testing "nor does a query outside a transaction"
        (is (empty? (jdbc/execute! (::pg/datasource db) ["SELECT * FROM orders"]))))
      (testing "the system can't be nested inside another transaction"
        (is (= ::perm/nested-transaction
               (:type (error #(perm/in-wall perms {:person peter :scopes #{acme}}
                                            (fn [tx] (perm/as-system (assoc perms :db tx) order-ids))))))))
      (finally (pg/stop db)))))

(deftest roles-and-grants-change-live
  (let [nina (id)]
    (join! *perms* paul acme nina "Production")
    (is (not (may? nina :orders/edit [:order acme-order])))
    (testing "an extra action, on the next check"
      (perm/grant-extra! *perms* {:by paul :scope acme :person nina :action :orders/edit})
      (is (may? nina :orders/edit [:order acme-order]))
      (perm/revoke-extra! *perms* {:by paul :scope acme :person nina :action :orders/edit})
      (is (not (may? nina :orders/edit [:order acme-order]))))
    (testing "a role's grid, on the next check"
      (perm/set-role-actions! *perms* {:by mila :scope acme :role "Production"
                                       :actions #{:orders/see :jobs/move :machines/use :orders/edit}})
      (is (may? nina :orders/edit [:order acme-order]))
      (perm/set-role-actions! *perms* {:by mila :scope acme :role "Production"
                                       :actions #{:orders/see :jobs/move :machines/use}}))
    (testing "removed, on the next check"
      (perm/remove-member! *perms* {:by paul :scope acme :person nina})
      (is (not (may? nina :orders/see [:order acme-order]))))))

(deftest only-what-you-hold-can-be-given
  (testing "managing the team is an action of its own"
    (is (= ::perm/refused (:type (error #(perm/invite! *perms* {:by peter :scope acme :email "x@x.example"
                                                               :role "Production" :days 7}))))))
  (testing "a manager can't hand out an action they don't hold"
    (is (= ::perm/refused (:type (error #(perm/set-role-actions! *perms* {:by mila :scope acme :role "Production"
                                                                         :actions #{:orders/see :money/move}}))))))
  (testing "only an owner makes owners"
    (is (= ::perm/refused (:type (error #(perm/change-role! *perms* {:by mila :scope acme :person peter :role "Owner"}))))))
  (testing "the owner role can't be edited"
    (is (= ::perm/refused (:type (error #(perm/set-role-actions! *perms* {:by paul :scope acme :role "Owner"
                                                                         :actions #{}}))))))
  (testing "a scope always keeps an owner"
    (is (= ::perm/refused (:type (error #(perm/remove-member! *perms* {:by paul :scope acme :person paul}))))))
  (testing "an invitation is the invited email's, once"
    (let [token (perm/invite! *perms* {:by paul :scope acme :email "Sam@Test.example" :role "Production" :days 7})]
      (is (= ::perm/not-found (:type (error #(perm/accept-invitation! *perms* {:token token :person (id) :email "eve@test.example"})))))
      (is (= acme (perm/accept-invitation! *perms* {:token token :person (id) :email "sam@test.example"}))
          "someone else's try doesn't use it up")
      (is (= ::perm/not-found (:type (error #(perm/accept-invitation! *perms* {:token token :person (id) :email "sam@test.example"}))))
          "but it works once"))))

(deftest the-description-is-checked
  (let [problem (fn [d] (:type (error #(perm/start {:settings d :db (:db *perms*)}))))]
    (is (= ::perm/bad-description
           (problem (assoc-in description [:records :machine :id] :machine/nope)))
        "a column that doesn't exist")
    (is (= ::rules/bad-description
           (problem (assoc-in description [:scopes :partner :roles "Member"] #{:money/move})))
        "a role holding another kind's action")
    (is (= ::rules/bad-description
           (problem (assoc-in description [:scopes :partner :actions] #{:agreements/see :orders/see})))
        "an action in two kinds")
    (is (= ::rules/bad-description
           (problem (assoc-in description [:scopes :shop :parent] :region)))
        "an unknown parent")
    (is (= ::rules/bad-description
           (problem (assoc-in description [:records :job :in] [:batch :job/order-id])))
        "an arrow to an unknown kind")
    (is (= ::rules/bad-description (problem (dissoc description :links))) "a missing key")))
