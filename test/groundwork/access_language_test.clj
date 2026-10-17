(ns groundwork.access-language-test
  "Each word of the access language, through a whole started app: the
   member words, a person on the row, a fixed scope and a kind with exactly
   one scope, :via in the direction the row points (a payout written by
   whoever may pay its order) and through a shared column (a partner's
   link), both denies, the browser's \"can I?\", the bells when who sees a
   row changes, and the start refusing what isn't a sentence. The direction
   where the related row points to this one is in shapes-test (handoffs),
   universal rows too."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.endpoints :as endpoints]
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions :as perm]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.repl :as repl]
   [groundwork.test-support :refer [app-settings sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket
                  WebSocket$Listener)
   (java.util UUID)
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- id [] (UUID/randomUUID))

;; places
(def acme (id)) (def zinc (id)) (def partner (id)) (def platform (id))
(def auditors #uuid "00000000-0000-4000-8000-0000000000a1")   ; one fixed scope, named by its id
;; people
(def acme-owner (id)) (def zinc-owner (id)) (def partner-owner (id)) (def platform-owner (id))
(def carla (id))   ; Clerk in Acme and in Zinc
(def zed (id))     ; Clerk in Zinc only
(def cash (id))    ; Cashier in Acme: may pay its orders
(def sue (id))     ; Support in the back office
(def aud (id))     ; the auditors' owner
(def kim (id))     ; Acme's customer, a member nowhere
(def vic (id))     ; a member nowhere
;; orders
(def a1 (id)) (def a2 (id)) (def z1 (id)) (def z2 (id))

(def tables
  "DROP TABLE IF EXISTS lg_payouts, lg_orders, lg_shops, lg_links;
   -- the app's own table of shops, where a shop's state lives
   CREATE TABLE lg_shops (shop__id uuid PRIMARY KEY, shop__status text NOT NULL);
   CREATE TABLE lg_orders (order__id uuid PRIMARY KEY, order__shop_id uuid NOT NULL, order__customer_id uuid,
                           order__status text NOT NULL, order__note text NOT NULL DEFAULT '');
   CREATE INDEX ON lg_orders (order__shop_id);
   CREATE TABLE lg_payouts (payout__id uuid PRIMARY KEY DEFAULT gen_random_uuid(), payout__shop_id uuid NOT NULL,
                            payout__order_id uuid NOT NULL, payout__cents bigint NOT NULL);
   CREATE INDEX ON lg_payouts (payout__order_id);
   -- a partner sees a shop's orders while their link is active
   CREATE TABLE lg_links (link__id uuid PRIMARY KEY DEFAULT gen_random_uuid(), link__shop_id uuid NOT NULL,
                          link__partner_id uuid NOT NULL, link__status text NOT NULL);
   CREATE INDEX lg_links_shop ON lg_links (link__shop_id);
   CREATE INDEX lg_links_partner ON lg_links (link__partner_id);")

(def description
  {:scopes  {:shop     {:actions {:orders/see    #{:read}
                                  :orders/edit   #{:read :update}
                                  :orders/pay    #{:read :insert}
                                  :payouts/write #{:read :insert}}
                        :roles   {"Clerk"   #{:orders/see :orders/edit}
                                  "Cashier" #{:orders/see :orders/pay}}
                        :parent  nil
                        :table   :lg_shops :id :shop/id}
             :partner  {:actions {:links/see #{:read}} :roles {} :parent nil}
             :platform {:actions {:platform/support #{:read}} :roles {"Support" #{:platform/support}}
                        :parent nil :single? true}
             :auditor  {:actions {:audit/orders #{:read}} :roles {} :parent nil}}
   :records {:order  {:table :lg_orders :id :order/id :whose {:order/shop-id :shop :order/customer-id :person}}
             :payout {:table :lg_payouts :id :payout/id :whose {:payout/shop-id :shop}}
             :link   {:table :lg_links :id :link/id :whose {:link/shop-id :shop :link/partner-id :partner}}}
   :rules   [{:allow #{:orders/see :orders/edit :orders/pay} :on :order :to [:member :order/shop-id]}
             {:allow :orders/see :on :order :to [:me :order/customer-id]}
             ;; through a shared column: the link names the order's shop
             {:allow :orders/see :on :order
              :via   {:table :lg_links :match {:order/shop-id :link/shop-id}
                      :to [:member :link/partner-id :links/see] :when [:= :link/status "active"]}}
             ;; a kind with exactly one scope reaches every shop's rows
             {:allow :orders/see :on :order :to [:member :platform :platform/support]}
             ;; and a fixed scope, named by its id
             {:allow :orders/see :on :order :to [:member auditors :audit/orders]}
             ;; the row points to the related one: a payout is written by whoever may pay its order
             {:allow :payouts/write :on :payout
              :via   {:table :lg_orders :match {:payout/order-id :order/id} :to [:member :order/shop-id :orders/pay]}}
             {:allow :links/see :on :link :to [:member :link/partner-id]}
             {:allow :orders/see :on :link :to [:member :link/shop-id]}
             ;; an impersonating admin can't move money, through any endpoint
             {:deny :payouts/write :session :impersonated}
             ;; a suspended shop is read-only
             {:deny :writes :scope :shop :when [:= :shop/status "suspended"]}]})

(pg/defsql lg-order)
(pg/defsql lg-orders)
(pg/defsql lg-orders-of-shop)
(pg/defsql lg-note-order)
(pg/defsql lg-close-all)
(pg/defsql lg-count-orders)
(pg/defsql lg-add-payout)

(def endpoint-map
  {:orders/one       {:read       (fn [{:keys [db]} params] (:order/id (pg/row db lg-order params)))
                      :permission {:action :orders/see :record [:order :order/id]}
                      :input      [:map [:order/id :uuid]]
                      :answer     [:maybe :uuid]}
   :orders/all       {:read       (fn [{:keys [db]} _] (mapv :order/id (pg/rows db lg-orders {})))
                      :permission {:action :orders/see :list :order}
                      :input      [:map]
                      :answer     [:vector :uuid]}
   :orders/of-shop   {:read       (fn [{:keys [db]} params] (mapv :order/id (pg/rows db lg-orders-of-shop params)))
                      :permission {:action :orders/see :scope [:shop :shop/id]}
                      :input      [:map [:shop/id :uuid]]
                      :answer     [:vector :uuid]}
   :orders/note      {:write      (fn [{:keys [db]} params] (:order/id (pg/row db lg-note-order params)))
                      :permission {:action :orders/edit :record [:order :order/id]}
                      :input      [:map [:order/id :uuid] [:order/note :string]]
                      :answer     [:maybe :uuid]}
   ;; closes every open order it may: a write about every scope the person has
   :orders/close-all {:write      (fn [{:keys [db]} _]
                                    {:closed (set (map :order/id (pg/rows db lg-close-all {})))
                                     :seen   (:orders/count (pg/row db lg-count-orders {}))})
                      :permission {:action :orders/edit :list :order}
                      :input      [:map]
                      :answer     [:map [:closed [:set :uuid]] [:seen :int]]}
   :payouts/add      {:write      (fn [{:keys [db]} params] (:payout/id (pg/row db lg-add-payout params)))
                      :permission {:action :orders/pay :record [:order :order/id]}
                      :input      [:map [:order/id :uuid] [:payout/cents :int]]
                      :answer     [:maybe :uuid]}})

(def ^:dynamic *app* nil)

(defn- join! [perms by scope person role]
  (let [email (str person "@test.example")
        token (perm/invite! perms {:by by :scope scope :email email :role role :days 7})]
    (perm/accept-invitation! perms {:token token :person person :email email})))

(defn- system! [sql & params]
  (perm/as-system (gw/permissions *app*) (fn [tx] (jdbc/execute! (::pg/conn tx) (into [sql] params)))))

(use-fixtures :once
  (fn [t]
    (sql! tables)
    (sql! "TRUNCATE groundwork.scopes, groundwork.roles, groundwork.role_actions, groundwork.memberships,
                    groundwork.extra_actions, groundwork.invitations CASCADE")
    (sql! (str "INSERT INTO lg_shops VALUES (?, 'active'), (?, 'active');"
               "INSERT INTO lg_orders (order__id, order__shop_id, order__customer_id, order__status)"
               " VALUES (?, ?, ?, 'open'), (?, ?, NULL, 'open'), (?, ?, NULL, 'open'), (?, ?, NULL, 'closed');"
               "INSERT INTO lg_links (link__shop_id, link__partner_id, link__status) VALUES (?, ?, 'active')")
          acme zinc a1 acme kim a2 acme z1 zinc z2 zinc acme partner)
    (let [app (gw/start (-> app-settings
                            (assoc :permissions description)
                            (assoc-in [:parquery :endpoints] [endpoint-map])))]
      (try
        (let [perms (gw/permissions app)]
          (perm/create-scope! perms {:kind :shop :id acme :parent nil :owner acme-owner})
          (perm/create-scope! perms {:kind :shop :id zinc :parent nil :owner zinc-owner})
          (perm/create-scope! perms {:kind :partner :id partner :parent nil :owner partner-owner})
          (perm/create-scope! perms {:kind :platform :id platform :parent nil :owner platform-owner})
          (perm/create-scope! perms {:kind :auditor :id auditors :parent nil :owner aud})
          (join! perms acme-owner acme carla "Clerk")
          (join! perms zinc-owner zinc carla "Clerk")
          (join! perms zinc-owner zinc zed "Clerk")
          (join! perms acme-owner acme cash "Cashier")
          (join! perms platform-owner platform sue "Support"))
        (binding [*app* app] (t))
        (finally
          (gw/stop app)
          (sql! "DROP TABLE IF EXISTS lg_payouts, lg_orders, lg_shops, lg_links"))))))

(defn- call [person endpoint params & {:keys [flags]}]
  (endpoints/call (gw/endpoints *app*) {:endpoint endpoint :params params :person person :flags (or flags #{})}))

(defn- may? [person action record & [flags]]
  (perm/allowed? (gw/permissions *app*) {:person person :action action :record record :flags (or flags #{})}))

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; =============================================================================
;; The words
;; =============================================================================

(deftest who-may-the-words-say
  (testing "[:member <column>]: a member of the row's scope whose role holds the action"
    (is (may? carla :orders/edit [:order a1]))
    (is (not (may? cash :orders/edit [:order a1])) "a Cashier's role doesn't hold it")
    (is (not (may? zed :orders/see [:order a1])) "another shop's member"))
  (testing "[:me <column>]: the person on the row"
    (is (may? kim :orders/see [:order a1]))
    (is (not (may? kim :orders/see [:order a2]))))
  (testing "[:member <single-scope kind> <action>]: the back office's support reaches every shop"
    (is (may? sue :orders/see [:order z2]))
    (is (= {:status :ok :data #{a1 a2 z1 z2}} (update (call sue :orders/all {}) :data set))
        "a list of every scope's orders")
    (is (= {:status :ok :data #{z1 z2}} (update (call sue :orders/of-shop {:shop/id zinc}) :data set)))
    (is (not (may? sue :orders/edit [:order z1])) "and only what the sentence says"))
  (testing "[:member <scope id> <action>]: a member of one fixed scope"
    (is (may? aud :orders/see [:order a2]))
    (is (not (may? vic :orders/see [:order a2]))))
  (testing "nobody else"
    (is (= {:status :not-found} (call vic :orders/one {:order/id a1})))
    (is (= {:status :ok :data []} (call vic :orders/all {})))))

(deftest a-related-row-gives-access
  (testing "through a shared column: the partner sees Acme's orders while their link is active"
    (is (may? partner-owner :orders/see [:order a1]))
    (is (not (may? partner-owner :orders/see [:order z1])) "no link to Zinc")
    (is (= #{a1 a2} (set (:data (call partner-owner :orders/all {})))))
    (system! "UPDATE lg_links SET link__status = 'ended' WHERE link__shop_id = ?" acme)
    (try
      (is (not (may? partner-owner :orders/see [:order a1])) "the link ended, so did the access")
      (is (= [] (:data (call partner-owner :orders/all {}))))
      (finally (system! "UPDATE lg_links SET link__status = 'active' WHERE link__shop_id = ?" acme))))
  (testing "the row points to the related one: a payout is written by whoever may pay its order"
    (let [payout (:data (call cash :payouts/add {:order/id a1 :payout/cents 500}))]
      (is (uuid? payout))
      (system! "DELETE FROM lg_payouts WHERE payout__id = ?" payout))
    (is (= {:status :not-found} (call carla :payouts/add {:order/id a1 :payout/cents 500}))
        "a Clerk may not pay")
    (testing "and the wall says the same, whatever the SQL"
      (is (thrown-with-msg? org.postgresql.util.PSQLException #"row-level security"
                            (perm/in-wall (gw/permissions *app*) {:person carla :scopes #{acme}}
                                          (fn [tx] (jdbc/execute! (::pg/conn tx)
                                                                  [(str "INSERT INTO lg_payouts (payout__shop_id, payout__order_id,"
                                                                        " payout__cents) VALUES (?, ?, 1)")
                                                                   acme a1])))))
      (is (thrown-with-msg? org.postgresql.util.PSQLException #"row-level security"
                            (perm/in-wall (gw/permissions *app*) {:person cash :scopes #{acme}}
                                          (fn [tx] (jdbc/execute! (::pg/conn tx)
                                                                  [(str "INSERT INTO lg_payouts (payout__shop_id, payout__order_id,"
                                                                        " payout__cents) VALUES (?, ?, 1)")
                                                                   acme z1]))))
          "an order she may not pay, though the payout names her shop"))))

;; =============================================================================
;; Denies
;; =============================================================================

(deftest a-session-flag-denies-at-the-wall-too
  (testing "an impersonating admin can't write a payout, though the endpoint's action is another"
    (is (= :error (:status (call cash :payouts/add {:order/id a1 :payout/cents 500} :flags #{:impersonated}))))
    (is (= [] (system! "SELECT 1 FROM lg_payouts WHERE payout__order_id = ?" a1))))
  (testing "the same, as the wall sees it"
    (is (thrown-with-msg? org.postgresql.util.PSQLException #"row-level security"
                          (perm/in-wall (gw/permissions *app*) {:person cash :scopes #{acme} :flags #{:impersonated}}
                                        (fn [tx] (jdbc/execute! (::pg/conn tx)
                                                                [(str "INSERT INTO lg_payouts (payout__shop_id, payout__order_id,"
                                                                      " payout__cents) VALUES (?, ?, 1)")
                                                                 acme a1]))))))
  (testing "it denies what it names, nothing else"
    (is (may? cash :orders/pay [:order a1] #{:impersonated}))
    (is (not (may? cash :payouts/write [:payout (id)] #{:impersonated})))))

(deftest a-scopes-state-denies
  (system! "UPDATE lg_shops SET shop__status = 'suspended' WHERE shop__id = ?" zinc)
  (try
    (testing "a suspended shop is read-only: reads answer"
      (is (= {:status :ok :data z1} (call zed :orders/one {:order/id z1})))
      (is (= {:status :ok :data #{z1 z2}} (update (call zed :orders/of-shop {:shop/id zinc}) :data set))))
    (testing "writes are not found"
      (is (= {:status :not-found} (call zed :orders/note {:order/id z1 :order/note "x"})))
      (is (not (may? zed :orders/edit [:order z1]))))
    (testing "a write about every shop of the person's: the suspended one stays readable and isn't written"
      (let [{:keys [closed seen]} (:data (call carla :orders/close-all {}))]
        (is (= #{a1 a2} closed))
        (is (= 4 seen) "Zinc's orders are verified for reads in the same transaction"))
      (is (= #{["open"]} (set (map vals (system! "SELECT order__status FROM lg_orders WHERE order__id = ?" z1))))))
    (testing "the REPL names the deny that said no"
      (let [w (repl/why *app* zed :orders/edit [:order z1])]
        (is (not (:allowed? w)))
        (is (some #(str/includes? % (str "Denied: shop " zinc " is in the state [:= :shop/status \"suspended\"]")) (:because w))
            (pr-str (:because w)))))
    (testing "\"can I?\" leaves the shop out of what writes, and keeps what reads"
      (let [grants (set (map (juxt :grant/scope-id :grant/action) (:data (call carla :groundwork/grants {}))))]
        (is (contains? grants [zinc :orders/see]))
        (is (not (contains? grants [zinc :orders/edit])))
        (is (contains? grants [acme :orders/edit]))))
    (finally
      (system! "UPDATE lg_shops SET shop__status = 'active' WHERE shop__id = ?" zinc)
      (system! "UPDATE lg_orders SET order__status = 'open' WHERE order__id IN (?, ?)" a1 a2))))

;; =============================================================================
;; Can I?
;; =============================================================================

(deftest can-i-comes-from-the-sentences
  (let [grants (fn [person] (set (map (juxt :grant/scope-id :grant/action) (:data (call person :groundwork/grants {})))))]
    (is (= #{[acme :orders/see] [acme :orders/edit] [zinc :orders/see] [zinc :orders/edit]} (grants carla))
        "a Clerk, through her role's actions in each shop, and nothing her role doesn't hold")
    (is (= #{[acme :orders/see] [acme :orders/pay]} (grants cash))
        "a payout's via names orders, not a shop: it gives no shop-wide grant")
    (is (contains? (grants partner-owner) [acme :orders/see]) "through the active link")
    (is (not (contains? (grants partner-owner) [zinc :orders/see])))
    (is (contains? (grants sue) [nil :orders/see]) "every scope: no scope named")
    (is (contains? (grants acme-owner) [acme :groundwork/team]))))

;; =============================================================================
;; The bells: a row that changes who sees it rings old and new viewers
;; =============================================================================

(def ^:private client (HttpClient/newHttpClient))
(def ^:private origin (get-in app-settings [:sessions :origin]))

(defn- sign-in [person]
  (let [r (.send client (-> (HttpRequest/newBuilder (URI. (str origin "/groundwork/test-sign-in?person=" person)))
                            (.header "X-Test-Secret" "a-secret-only-tests-know")
                            (.POST (HttpRequest$BodyPublishers/noBody))
                            .build)
                 (HttpResponse$BodyHandlers/ofString))]
    (first (str/split (.orElse (.firstValue (.headers r) "set-cookie") "") #";"))))

(defn- ask! [cookie endpoint params]
  (let [r (.send client (-> (HttpRequest/newBuilder (URI. (str origin "/parquery")))
                            (.header "Origin" origin) (.header "Content-Type" "application/json")
                            (.header "Cookie" cookie)
                            (.POST (HttpRequest$BodyPublishers/ofString
                                    (wire/write-json [{:endpoint endpoint :params params}])))
                            .build)
                 (HttpResponse$BodyHandlers/ofString))]
    (first (wire/read-json (.body r)))))

(defn- open-line [cookie]
  (let [messages (LinkedBlockingQueue.)
        listener (reify WebSocket$Listener
                   (onText [_ ws data _last] (.add messages (str data)) (.request ws 1) nil))
        ws       (.get (.buildAsync (-> (.newWebSocketBuilder client) (.header "Origin" origin) (.header "Cookie" cookie))
                                    (URI. (str (str/replace origin "http" "ws") "/parquery"))
                                    listener)
                       5 TimeUnit/SECONDS)]
    {:ws ws :messages messages}))

(defn- watch! [{:keys [^WebSocket ws]} & asks]
  (.get (.sendText ws (wire/write-json {:watch (vec asks)}) true) 5 TimeUnit/SECONDS)
  (Thread/sleep 300))

(defn- next-bell [{:keys [^LinkedBlockingQueue messages]} ms]
  (some-> (.poll messages ms TimeUnit/MILLISECONDS) wire/read-json :changed))

(defn- close! [{:keys [^WebSocket ws]}]
  (try (.get (.sendClose ws WebSocket/NORMAL_CLOSURE "") 5 TimeUnit/SECONDS) (catch Exception _ nil)))

(deftest a-row-that-changes-viewers-rings-old-and-new
  (let [acme-cookie  (sign-in acme-owner)
        zed-cookie   (sign-in zed)
        pia-cookie   (sign-in partner-owner)
        acme-list    ["orders/of-shop" {:shop/id (str acme)}]
        zinc-list    ["orders/of-shop" {:shop/id (str zinc)}]
        all          ["orders/all" {}]
        _            (ask! acme-cookie "orders/of-shop" {:shop/id (str acme)})
        _            (ask! zed-cookie "orders/of-shop" {:shop/id (str zinc)})
        _            (ask! pia-cookie "orders/all" {})
        acme-line    (open-line acme-cookie)
        zinc-line    (open-line zed-cookie)
        pia-line     (open-line pia-cookie)]
    (try
      (watch! acme-line acme-list)
      (watch! zinc-line zinc-list)
      (watch! pia-line all)
      (testing "an order moving from Acme to Zinc: Acme's watcher, who loses it, and Zinc's, who gains it"
        (system! "UPDATE lg_orders SET order__shop_id = ? WHERE order__id = ?" zinc a2)
        (is (= [acme-list] (next-bell acme-line 3000)))
        (is (= [zinc-list] (next-bell zinc-line 3000)))
        (is (= [all] (next-bell pia-line 3000)) "the partner saw it through Acme's link"))
      (testing "the link the partner sees Acme through ends: their list is asked again"
        (system! "UPDATE lg_links SET link__status = 'ended' WHERE link__shop_id = ?" acme)
        (is (= [all] (next-bell pia-line 3000)))
        (is (= {:status "ok" :data []} (ask! pia-cookie "orders/all" {}))))
      (testing "a new link, to a shop the partner saw nothing of: heard, though their reads were in no scope"
        (watch! pia-line all)
        (system! "INSERT INTO lg_links (link__shop_id, link__partner_id, link__status) VALUES (?, ?, 'active')"
                 zinc partner)
        (is (= [all] (next-bell pia-line 3000))))
      (finally
        (system! "UPDATE lg_orders SET order__shop_id = ? WHERE order__id = ?" acme a2)
        (system! "UPDATE lg_links SET link__status = 'active' WHERE link__shop_id = ?" acme)
        (system! "DELETE FROM lg_links WHERE link__shop_id = ?" zinc)
        (doseq [line [acme-line zinc-line pia-line]] (close! line))))))

;; =============================================================================
;; The start reads every sentence
;; =============================================================================

(defn- sentence-problem
  "The type of what the start says of the description with one more sentence."
  [sentence]
  (:type (error #(rules/check (update description :rules conj sentence)))))

(deftest the-start-refuses-what-isnt-a-sentence
  (testing "an unknown word"
    (is (= ::rules/bad-description (sentence-problem {:allow :orders/see :on :order :to [:owner :order/shop-id]}))))
  (testing "a member of a column that names a person, not a scope"
    (is (= ::rules/bad-description (sentence-problem {:allow :orders/see :on :order :to [:member :order/customer-id]}))))
  (testing "an action nobody in that scope can hold"
    (is (= ::rules/bad-description (sentence-problem {:allow :orders/see :on :order :to [:member :order/shop-id :links/see]})))
    (is (= ::rules/bad-description (sentence-problem {:allow :links/see :on :order :to [:member :order/shop-id]}))
        "a shop's role can't hold a partner's action: name the action held there"))
  (testing "[:member <kind>] names a kind with exactly one scope"
    (is (= ::rules/bad-description (sentence-problem {:allow :orders/see :on :order :to [:member :shop :orders/see]}))))
  (testing "[:none …] of a column that isn't a scope column"
    (is (= ::rules/bad-description (sentence-problem {:allow :orders/see :on :order :to :anyone
                                                      :when [:none :order/customer-id]}))))
  (testing "an unknown kind, an unknown action"
    (is (= ::rules/bad-description (sentence-problem {:allow :orders/see :on :invoice :to :anyone})))
    (is (= ::rules/bad-description (sentence-problem {:allow :orders/burn :on :order :to :anyone}))))
  (testing "a scope's state lives in the app's table, which the kind names"
    (is (= ::rules/bad-description (sentence-problem {:deny :writes :scope :partner :when [:= :partner/status "x"]}))))
  (testing "an action that updates without reading"
    (is (= ::rules/bad-description
           (:type (error #(rules/check (assoc-in description [:scopes :shop :actions :orders/edit] #{:update}))))))))

(deftest the-start-checks-the-sentences-against-the-database
  (let [start (fn [d] (error #(perm/start {:settings d :db (gw/db *app*)})))]
    (try
      (testing "a column the table doesn't have"
        (is (= ::perm/bad-description
               (:type (start (update description :rules conj
                                     {:allow :orders/see :on :order :to :anyone :when [:= :order/colour "red"]}))))))
      (testing "[:none …] of a column that can't be empty"
        (let [e (start (update description :rules conj
                               {:allow :orders/see :on :order :to :anyone :when [:none :order/shop-id]}))]
          (is (= ::perm/bad-description (:type e)))
          (is (= "ALTER TABLE lg_orders ALTER COLUMN order__shop_id DROP NOT NULL;" (:sql e)))))
      (testing "a via's who column needs an index: the related rows are found by it"
        (sql! "DROP INDEX lg_links_partner")
        (try
          (let [e (start description)]
            (is (= ::perm/bad-description (:type e)))
            (is (= "CREATE INDEX ON lg_links (link__partner_id);" (:sql e))))
          (finally (sql! "CREATE INDEX lg_links_partner ON lg_links (link__partner_id)"))))
      (finally
        ;; the app's own wall back
        (perm/start {:settings description :db (gw/db *app*)})))))
