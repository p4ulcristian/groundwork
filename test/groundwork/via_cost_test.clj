(ns groundwork.via-cost-test
  "What a :via costs on a long list, measured: 100 shops of 1 000 orders
   (100 000 rows), and as many payouts. Runs only with GROUNDWORK_MEASURE=1
   (it fills the tables for a while); prints Postgres's own execution time
   (EXPLAIN ANALYZE), the median of five runs, for:

   - a member's list of their shop's orders, and of every shop's, through
     their role (no via: the baseline);
   - a partner's list through a shared column (the link names the shop),
     linked to half the shops;
   - a receiving shop's list through rows that point to the order (100 open
     handoffs);
   - a cashier's list of payouts through the order each points to (the via
     offers every order of the shop);
   - each through the wall (an endpoint's transaction) and through the list
     filter (the same sentences as one WHERE, read as the system)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [groundwork.migrations :as migrations]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [db-settings sql!]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.util UUID)))

(def measure? (= "1" (System/getenv "GROUNDWORK_MEASURE")))

(def shops (vec (repeatedly 100 #(UUID/randomUUID))))
(def partner (UUID/randomUUID))
(def owner (UUID/randomUUID)) (def clerk (UUID/randomUUID)) (def boss (UUID/randomUUID))
(def cashier (UUID/randomUUID)) (def receiver (UUID/randomUUID)) (def partner-owner (UUID/randomUUID))

(def description
  {:scopes  {:shop    {:actions {:orders/see #{:read} :orders/pay #{:read :insert} :handoffs/work #{:read}}
                       :roles   {"Clerk" #{:orders/see} "Cashier" #{:orders/pay} "Receiver" #{:handoffs/work}}
                       :parent  nil}
             :partner {:actions {:links/see #{:read}} :roles {} :parent nil}}
   :records {:order   {:table :vc_orders :id :order/id :whose {:order/shop-id :shop}}
             :link    {:table :vc_links :id :link/id :whose {:link/shop-id :shop :link/partner-id :partner}}
             :handoff {:table :vc_handoffs :id :handoff/id :whose {:handoff/to-shop-id :shop}}
             :payout  {:table :vc_payouts :id :payout/id :whose {:payout/shop-id :shop}}}
   :rules   [{:allow #{:orders/see :orders/pay} :on :order :to [:member :order/shop-id]}
             {:allow :orders/see :on :order
              :via {:table :vc_links :match {:order/shop-id :link/shop-id}
                    :to [:member :link/partner-id :links/see] :when [:= :link/status "active"]}}
             {:allow :orders/see :on :order
              :via {:table :vc_handoffs :match {:order/id :handoff/order-id}
                    :to [:member :handoff/to-shop-id :handoffs/work] :when [:= :handoff/status "open"]}}
             {:allow :orders/pay :on :payout
              :via {:table :vc_orders :match {:payout/order-id :order/id} :to [:member :order/shop-id :orders/pay]}}
             {:allow :links/see :on :link :to [:member :link/partner-id]}
             {:allow :handoffs/work :on :handoff :to [:member :handoff/to-shop-id]}]})

(def tables
  "DROP TABLE IF EXISTS vc_orders, vc_links, vc_handoffs, vc_payouts;
   CREATE TABLE vc_orders (order__id uuid PRIMARY KEY, order__shop_id uuid NOT NULL, order__n int NOT NULL);
   CREATE INDEX ON vc_orders (order__shop_id);
   CREATE TABLE vc_links (link__id uuid PRIMARY KEY DEFAULT gen_random_uuid(), link__shop_id uuid NOT NULL,
                          link__partner_id uuid NOT NULL, link__status text NOT NULL);
   CREATE INDEX ON vc_links (link__shop_id); CREATE INDEX ON vc_links (link__partner_id);
   CREATE TABLE vc_handoffs (handoff__id uuid PRIMARY KEY DEFAULT gen_random_uuid(), handoff__order_id uuid NOT NULL,
                             handoff__to_shop_id uuid NOT NULL, handoff__status text NOT NULL);
   CREATE INDEX ON vc_handoffs (handoff__order_id); CREATE INDEX ON vc_handoffs (handoff__to_shop_id);
   CREATE TABLE vc_payouts (payout__id uuid PRIMARY KEY DEFAULT gen_random_uuid(), payout__shop_id uuid NOT NULL,
                            payout__order_id uuid NOT NULL);
   CREATE INDEX ON vc_payouts (payout__order_id); CREATE INDEX ON vc_payouts (payout__shop_id);")

(def ^:dynamic *perms* nil)

(defn- join! [perms by scope person role]
  (let [email (str person "@test.example")
        token (perm/invite! perms {:by by :scope scope :email email :role role :days 7})]
    (perm/accept-invitation! perms {:token token :person person :email email})))

(use-fixtures :once
  (fn [t]
    (if-not measure?
      (t)
      (let [db (pg/start db-settings)]
        (try
          (sql! tables)
          (sql! (str "INSERT INTO vc_orders SELECT gen_random_uuid(), (ARRAY[" (str/join "," (map #(str "'" % "'::uuid") shops))
                     "])[1 + (g % 100)], g FROM generate_series(0, 99999) g"))
          (sql! (str "INSERT INTO vc_links (link__shop_id, link__partner_id, link__status) SELECT s, ?, 'active'"
                     " FROM unnest(CAST(? AS uuid[])) s")
                partner (str "{" (str/join "," (take 50 shops)) "}"))
          (sql! (str "INSERT INTO vc_handoffs (handoff__order_id, handoff__to_shop_id, handoff__status)"
                     " SELECT order__id, ?, 'open' FROM vc_orders ORDER BY order__n LIMIT 100")
                (last shops))
          (sql! "INSERT INTO vc_payouts (payout__shop_id, payout__order_id) SELECT order__shop_id, order__id FROM vc_orders")
          (sql! "ANALYZE vc_orders; ANALYZE vc_links; ANALYZE vc_handoffs; ANALYZE vc_payouts")
          (migrations/migrate! db)
          (sql! "TRUNCATE groundwork.scopes, groundwork.roles, groundwork.role_actions, groundwork.memberships,
                          groundwork.extra_actions, groundwork.invitations CASCADE")
          (let [perms (perm/start {:settings description :db db})]
            (doseq [s shops] (perm/create-scope! perms {:kind :shop :id s :parent nil :owner owner}))
            (perm/create-scope! perms {:kind :partner :id partner :parent nil :owner partner-owner})
            (join! perms owner (first shops) clerk "Clerk")
            (join! perms owner (first shops) cashier "Cashier")
            (join! perms owner (last shops) receiver "Receiver")
            (doseq [s shops] (join! perms owner s boss "Clerk"))
            (binding [*perms* perms] (t)))
          (finally
            (pg/stop db)
            (sql! "DROP TABLE IF EXISTS vc_orders, vc_links, vc_handoffs, vc_payouts")))))))

(defn- ms
  "Postgres's execution time of a query, the median of five runs, in ms."
  [run-in sql params]
  (when (System/getenv "GROUNDWORK_MEASURE_PLAN")
    (run-in (fn [tx]
              (jdbc/execute! (::pg/conn tx) ["SET LOCAL statement_timeout = 0"])
              (println "----" sql)
              (doseq [r (jdbc/execute! (::pg/conn tx) (into [(str "EXPLAIN (ANALYZE) " sql)] params))]
                (println (first (vals r)))))))
  (let [times (for [_ (range 5)]
                (run-in (fn [tx]
                          (jdbc/execute! (::pg/conn tx) ["SET LOCAL statement_timeout = 0"])
                          (let [plan (map (comp first vals)
                                          (jdbc/execute! (::pg/conn tx) (into [(str "EXPLAIN (ANALYZE) " sql)] params)))]
                            (some #(some->> % (re-find #"Execution Time: ([0-9.]+)") second parse-double) plan)))))]
    (nth (sort times) 2)))

(defn- rows [run-in sql params]
  (run-in (fn [tx] (jdbc/execute! (::pg/conn tx) ["SET LOCAL statement_timeout = 0"]) (:n (jdbc/execute-one! (::pg/conn tx) (into [(str "SELECT count(*) AS n FROM (" sql ") x")] params)
                                          {:builder-fn rs/as-unqualified-maps})))))

(defn- line [what person action kind table]
  (let [{:keys [scopes every?]} (perm/scopes-listed *perms* {:person person :flags #{} :action action :kind kind})
        wall      #(perm/in-wall *perms* {:person person :scopes (if every? :every scopes) :read-only? true} %)
        system    #(perm/as-system *perms* %)
        {:keys [sql params]} (perm/list-filter *perms* {:person person :flags #{} :action action :kind kind})
        through-wall (str "SELECT * FROM " table)
        filtered     (str "SELECT * FROM " table " r WHERE " sql)]
    {:what what
     :rows (rows wall through-wall [])
     :wall (ms wall through-wall [])
     :filter-rows (rows system filtered params)
     :filter (ms system filtered params)}))

(deftest the-cost-of-a-via
  (when measure?
    (let [lines [(line "member, one shop (no via)" clerk :orders/see :order "vc_orders")
                 (line "member of all 100 shops (no via)" boss :orders/see :order "vc_orders")
                 (line "partner, shared column, 50 shops" partner-owner :orders/see :order "vc_orders")
                 (line "receiver, 100 handoffs point to orders" receiver :orders/see :order "vc_orders")
                 (line "cashier, payouts point to orders" cashier :orders/pay :payout "vc_payouts")]]
      (println)
      (println "| list | rows | wall ms | list filter rows | list filter ms |")
      (println "|---|---|---|---|---|")
      (doseq [{:keys [what rows wall filter-rows filter]} lines]
        (println (str "| " what " | " rows " | " wall " | " filter-rows " | " filter " |")))
      (let [one (perm/as-system *perms* (fn [tx] (:payout/id (first (jdbc/execute! (::pg/conn tx)
                                                                                   ["SELECT payout__id FROM vc_payouts WHERE payout__shop_id = ? LIMIT 1"
                                                                                    (first shops)])))))
            t0  (System/nanoTime)
            _   (dotimes [_ 20] (perm/allowed? *perms* {:person cashier :flags #{} :action :orders/pay :record [:payout one]}))]
        (println (str "one payout's check (allowed?), through the via, mean of 20: "
                      (format "%.1f" (/ (- (System/nanoTime) t0) 20 1e6)) " ms")))
      (is (= 1000 (:rows (first lines))))
      (is (= 50000 (:rows (nth lines 2))))
      (is (= 100 (:rows (nth lines 3))))
      (is (= 1000 (:rows (nth lines 4)))))))
