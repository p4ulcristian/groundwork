(ns groundwork.shapes-test
  "The permission shapes on the example app (example.shapes.app), through a
   whole started app: real tables, real endpoints, the wall and the bells."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [example.shapes.app :as shapes]
   [groundwork.core :as gw]
   [groundwork.endpoints :as endpoints]
   [groundwork.leaks :as leaks]
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions :as perm]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.sessions :as sessions]
   [groundwork.test-support :refer [app-settings sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket
                  WebSocket$Listener)
   (java.util UUID)
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- id [] (UUID/randomUUID))

;; places and people
(def acme (id)) (def zinc (id))
(def sam (id))    ; Acme's owner
(def stella (id)) ; Acme's staff
(def olga (id))   ; Zinc's owner
(def pat (id))    ; the platform's owner
(def vic (id))    ; signed in, a member nowhere
;; records
(def gloss (id)) (def acme-secret (id)) (def zinc-matte (id)) (def zinc-secret (id))
(def acme-order (id)) (def zinc-order (id))
(def shared (id)) (def shared-draft (id))   ; the platform's own packages
(def pine (id))   ; a partner: a scope like a shop
(def piet (id))   ; Pine's owner
(def acme-pine (id)) (def zinc-pine (id))   ; agreements
(def zinc-price (id)) (def zinc-service (id))
(def petra (id))  ; Pine's production
(def paulus (id)) ; Pine's bookkeeper
(def acme-order-2 (id)) (def acme-job (id)) (def acme-job-2 (id)) (def zinc-job (id)) (def zinc-handoff (id)) (def zinc-pine-handoff (id))
(def uma (id))    ; signed in, a member nowhere, like Vic
(def talk (id)) (def later-talk (id)) (def zinc-talk (id)) (def zinc-message (id))

(def ^:dynamic *app* nil)

(defn- settings [description endpoint-map]
  (-> app-settings
      (assoc :permissions description)
      ;; the app's own login, on a browser route: the request carries the visitor's session
      (assoc-in [:web :routes]
                [["/login-as/:person"
                  {:post   (fn [request]
                             (sessions/sign-in request {:status 200 :headers {} :body "signed in"}
                                               (parse-uuid (get-in request [:path-params :person]))))
                   :access :public}]])
      (assoc-in [:parquery :endpoints] [endpoint-map])))

(defn- join! [perms by scope person role]
  (let [email (str person "@test.example")
        token (perm/invite! perms {:by by :scope scope :email email :role role :days 7})]
    (perm/accept-invitation! perms {:token token :person person :email email})))

(defn- system! [perms sql & params]
  (perm/as-system perms (fn [tx] (jdbc/execute! (::pg/conn tx) (into [sql] params)))))

(defn- seed! [perms]
  (system! perms (str "INSERT INTO sh_packages VALUES (?, ?, 'Gloss', true), (?, ?, 'Secret', false),"
                      " (?, ?, 'Zinc Matte', true), (?, ?, 'Zinc Secret', false)")
           gloss acme acme-secret acme zinc-matte zinc zinc-secret zinc)
  (system! perms "INSERT INTO sh_packages VALUES (?, ?, 'Shared Primer', true), (?, ?, 'Shared Draft', false)"
           shared shapes/platform shared-draft shapes/platform)
  (system! perms "INSERT INTO sh_orders VALUES (?, ?, ?, 'a'), (?, ?, NULL, 'z')"
           acme-order acme vic zinc-order zinc)
  (system! perms "INSERT INTO sh_orders VALUES (?, ?, NULL, 'b')" acme-order-2 acme)
  (system! perms "INSERT INTO sh_jobs VALUES (?, ?, ?, 'cut'), (?, ?, ?, 'paint'), (?, ?, ?, 'pack job')"
           acme-job acme-order acme acme-job-2 acme-order-2 acme zinc-job zinc-order zinc)
  ;; a handoff that is done: a pass that gives nothing any more
  (system! perms (str "INSERT INTO sh_handoffs (handoff__id, handoff__order_id, handoff__from_shop_id,"
                      " handoff__to_shop_id, handoff__status) VALUES (?, ?, ?, ?, 'done')")
           zinc-handoff zinc-order zinc acme)
  (system! perms (str "INSERT INTO sh_handoffs (handoff__id, handoff__order_id, handoff__from_shop_id,"
                      " handoff__to_shop_id, handoff__status) VALUES (?, ?, ?, ?, 'done')")
           zinc-pine-handoff zinc-order zinc pine)
  (system! perms "INSERT INTO sh_agreements VALUES (?, ?, ?), (?, ?, ?)" acme-pine acme pine zinc-pine zinc pine)
  (system! perms "INSERT INTO sh_price_sheets (price_sheet__id, price_sheet__agreement_id, price_sheet__cents) VALUES (?, ?, 900)"
           zinc-price zinc-pine)
  (system! perms (str "INSERT INTO sh_order_services (order_service__id, order_service__order_id, order_service__name)"
                      " VALUES (?, ?, 'Deep cleaning')")
           zinc-service zinc-order)
  (system! perms "INSERT INTO sh_conversations VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, NULL), (?, ?, ?, ?, NULL)"
           talk acme pine shapes/platform vic
           later-talk acme pine shapes/platform
           zinc-talk zinc pine shapes/platform)
  (system! perms "INSERT INTO sh_messages (message__id, message__conversation_id, message__text) VALUES (?, ?, 'zinc says')"
           zinc-message zinc-talk))

(use-fixtures :once
  (fn [t]
    (sql! shapes/tables)
    (sql! "TRUNCATE groundwork.scopes, groundwork.roles, groundwork.role_actions, groundwork.memberships,
                    groundwork.extra_actions, groundwork.invitations CASCADE")
    (let [app (gw/start (settings shapes/description shapes/endpoints))]
      (try
        (let [perms (gw/permissions app)]
          (perm/create-scope! perms {:kind :shop :id acme :parent nil :owner sam})
          (perm/create-scope! perms {:kind :shop :id zinc :parent nil :owner olga})
          (perm/create-scope! perms {:kind :shop :id shapes/platform :parent nil :owner pat})
          (perm/create-scope! perms {:kind :shop :id pine :parent nil :owner piet})
          (join! perms sam acme stella "Staff")
          (join! perms piet pine petra "Production")
          (join! perms piet pine paulus "Bookkeeper")
          (seed! perms))
        (binding [*app* app] (t))
        (finally
          (gw/stop app)
          (sql! shapes/drop-tables))))))

(defn- call
  ([person endpoint params] (call person endpoint params nil))
  ([person endpoint params key]
   (endpoints/call (gw/endpoints *app*) (cond-> {:endpoint endpoint :params params :person person :flags #{}}
                                          key (assoc :key key)))))

(defn- names [answer] (mapv :package/name (:data answer)))

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- as-system! [sql & params]
  (apply system! (gw/permissions *app*) sql params))

;; =============================================================================
;; Over the wire
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

(defn- ask!
  "One ask over HTTP; nobody signed in when cookie is nil."
  [cookie endpoint params]
  (let [r (.send client (-> (cond-> (-> (HttpRequest/newBuilder (URI. (str origin "/parquery")))
                                        (.header "Origin" origin)
                                        (.header "Content-Type" "application/json"))
                              cookie (.header "Cookie" cookie))
                            (.POST (HttpRequest$BodyPublishers/ofString
                                    (wire/write-json [{:endpoint endpoint :params params :key (str (id))}])))
                            .build)
                 (HttpResponse$BodyHandlers/ofString))]
    (first (wire/read-json (.body r)))))

(defn- open-line [cookie]
  (let [messages (LinkedBlockingQueue.)
        listener (reify WebSocket$Listener
                   (onText [_ ws data _last] (.add messages (str data)) (.request ws 1) nil)
                   (onClose [_ _ws _code _reason] (.add messages "closed") nil))
        ws       (.get (.buildAsync (cond-> (.header (.newWebSocketBuilder client) "Origin" origin)
                                      cookie (.header "Cookie" cookie))
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
  (when-not (.isOutputClosed ws)
    (try (.get (.sendClose ws WebSocket/NORMAL_CLOSURE "") 5 TimeUnit/SECONDS)
         (catch Exception _ nil))))

;; =============================================================================
;; An action can be open
;; =============================================================================

(deftest an-open-action-needs-no-membership
  (testing "nobody signed in reads a shop's published packages, and only those"
    (is (= ["Gloss"] (names (call nil :catalogue/of-shop {:shop/id acme}))))
    (is (= ["Zinc Matte"] (names (call nil :catalogue/of-shop {:shop/id zinc})))))
  (testing "over HTTP, signed out"
    (is (= {:status "ok" :data [{:package/id (str gloss) :package/name "Gloss"}]}
           (ask! nil "catalogue/of-shop" {:shop/id (str acme)}))))
  (testing "one record: the published one answers, the unpublished one isn't there"
    (is (= "Gloss" (get-in (call nil :catalogue/package {:package/id gloss}) [:data :package/name])))
    (is (= {:status :not-found} (call nil :catalogue/package {:package/id acme-secret}))))
  (testing "a scope that doesn't exist is not found"
    (is (= {:status :not-found} (call nil :catalogue/of-shop {:shop/id (id)}))))
  (testing "a member gets the same rows from the open read: its rule is everyone's"
    (is (= ["Gloss"] (names (call stella :catalogue/of-shop {:shop/id acme}))))))

(deftest the-wall-shows-an-open-action-only-its-own-kinds
  (testing "the same open action with SQL that reaches for orders: a stranger sees none"
    (is (= {:status :ok :data 0} (call nil :catalogue/busy? {:shop/id acme})))
    (is (= {:status :ok :data 0} (call olga :catalogue/busy? {:shop/id acme})) "another shop's owner too"))
  (testing "a member of the shop sees its orders, as a member"
    (is (= {:status :ok :data 2} (call stella :catalogue/busy? {:shop/id acme}))))
  (testing "a person's own record stays theirs: Vic's order in Acme"
    (is (= {:status :ok :data 1} (call vic :catalogue/busy? {:shop/id acme})))))

(deftest open-to-the-signed-in-asks-for-a-session
  (is (= {:status :signed-out} (call nil :shop/card {:shop/id acme})))
  (is (= {:status :ok :data 1} (call vic :shop/card {:shop/id acme})) "signed in, a member nowhere")
  (is (= "signed-out" (:status (ask! nil "shop/card" {:shop/id (str acme)}))))
  (is (= {:status "ok" :data 1} (ask! (sign-in vic) "shop/card" {:shop/id (str acme)}))))

(deftest an-open-action-only-reads-and-nobody-holds-it
  (testing "a member's write still needs its own action"
    (is (= {:status :not-found} (call vic :catalogue/rename {:package/id gloss :package/name "X"})))
    (is (= "Gloss 2" (get-in (call stella :catalogue/rename {:package/id gloss :package/name "Gloss 2"})
                             [:data :package/name])))
    (as-system! "UPDATE sh_packages SET package__name = 'Gloss' WHERE package__id = ?" gloss))
  (testing "a role can't hold it"
    (is (= ::rules/bad-description
           (:type (error #(rules/check (assoc-in shapes/description [:scopes :shop :roles "Staff"]
                                                 #{:catalogue/see})))))))
  (testing "it can't be given as an extra action"
    (is (= ::perm/unknown-action
           (:type (error #(perm/grant-extra! (gw/permissions *app*)
                                             {:by sam :scope acme :person stella :action :catalogue/see}))))))
  (testing "a write can't declare it, nor a list of every scope's"
    (let [start (fn [entry] (error #(endpoints/start {:settings     (assoc (get-in (settings shapes/description {}) [:parquery])
                                                                          :endpoints [{:x/y entry}])
                                                      :db           (gw/db *app*)
                                                      :permissions  (gw/permissions *app*)
                                                      :app-settings {}})))]
      (is (= ::endpoints/bad-endpoint
             (:type (start {:write      (fn [_ _] nil)
                            :permission {:action :catalogue/see :scope [:shop :shop/id]}
                            :input      [:map [:shop/id :uuid]] :answer :nil}))))
      (is (= ::endpoints/bad-endpoint
             (:type (start {:read       (fn [_ _] nil)
                            :permission {:action :catalogue/see :list :package}
                            :input      [:map] :answer :nil}))))
      (testing "nor carry ids of a kind it doesn't reach"
        (is (= ::endpoints/bad-endpoint
               (:type (start {:read       (fn [_ _] nil)
                              :permission {:action :catalogue/see :scope [:shop :shop/id]}
                              :input      [:map [:shop/id :uuid] [:order/id {:groundwork/record :order} :uuid]]
                              :answer     :nil}))))))))

(deftest the-leak-test-covers-open-actions
  (let [part   (gw/endpoints *app*)
        sealed #{acme-secret zinc-secret shared-draft acme-order zinc-order}
        report (leaks/check part {:person  stella
                                  :foreign {:shop zinc :package zinc-secret :order zinc-order
                                            :agreement zinc-pine :price-sheet zinc-price
                                            :order-service zinc-service
                                            :conversation zinc-talk :message zinc-message
                                            :job zinc-job :handoff zinc-pine-handoff}
                                  :sealed  sealed})]
    (is (= [] (:leaked report)))
    (is (every? (set (:checked report)) [:catalogue/of-shop :catalogue/package :catalogue/busy? :shop/card
                                         :catalogue/rename :orders/of-shop :prices/of-agreement :prices/add
                                         :services/mine :services/add :orders/note
                                         :talk/messages :talk/say :handoffs/give :handoffs/set-status
                                         :orders/incoming :jobs/of-order :jobs/one :orders/stamp]))
    (testing "as the holder of a pass on one order, asking for the giving shop's other records"
      (let [handoff (:data (call sam :handoffs/give {:order/id acme-order :handoff/to-shop-id pine
                                                     :handoff/driver-id nil}))]
        (try
          (is (= [] (:leaked (leaks/check part {:person  petra
                                                :foreign {:shop acme :order acme-order-2 :job acme-job-2
                                                          :package acme-secret}
                                                :sealed  sealed}))))
          (finally (as-system! "DELETE FROM sh_handoffs WHERE handoff__id = ?" handoff)))))
    (testing "the wall, asked directly: a stranger sees nothing it shouldn't, of any kind"
      (is (= [] (leaks/open-wall (gw/permissions *app*) {:shop acme}))))
    (testing "a kind that forgot its condition shows its unpublished rows, and the test says so"
      (let [broken (update-in shapes/description [:records :package] dissoc :only-when)
            perms  (perm/start {:settings broken :db (gw/db *app*)})
            part   (endpoints/start {:settings     (get-in (settings broken shapes/endpoints) [:parquery])
                                     :db           (gw/db *app*)
                                     :permissions  perms
                                     :app-settings {}})]
        (try
          (is (= #{:catalogue/package :catalogue/of-shop :catalogue/for-shop}
                 (set (keep :endpoint (:leaked (leaks/check part {:person  stella
                                                                  :foreign {:shop zinc :package zinc-secret
                                                                            :order zinc-order}
                                                                  :sealed  sealed}))))))
          (finally
            ;; the app's own wall back
            (perm/start {:settings shapes/description :db (gw/db *app*)})))))))

;; =============================================================================
;; A record of two scopes may have a parent and children
;; =============================================================================

(deftest a-record-under-two-scopes-carries-both
  (testing "a price added under an agreement: the SQL names no scope, both are the agreement's"
    (let [price (:data (call sam :prices/add {:agreement/id acme-pine :price-sheet/cents 1200}))]
      (is (uuid? price))
      (is (= [{:sh_price_sheets/price_sheet__shop_id acme :sh_price_sheets/price_sheet__partner_id pine}]
             (as-system! "SELECT price_sheet__shop_id, price_sheet__partner_id FROM sh_price_sheets WHERE price_sheet__id = ?"
                         price)))))
  (testing "either side reads and writes it; nobody else sees it"
    (is (= {:status :ok :data [1200]} (call piet :prices/of-agreement {:agreement/id acme-pine})) "the partner")
    (is (uuid? (:data (call piet :prices/add {:agreement/id acme-pine :price-sheet/cents 1500}))))
    (is (= {:status :ok :data [1200 1500]} (call stella :prices/of-agreement {:agreement/id acme-pine})) "the shop")
    (is (= {:status :not-found} (call olga :prices/of-agreement {:agreement/id acme-pine})) "another shop")
    (is (= {:status :ok :data [900]} (call piet :prices/of-agreement {:agreement/id zinc-pine}))
        "the partner's other agreement, and only its own prices"))
  (testing "the database refuses a row whose scopes aren't its parent's"
    (is (thrown? org.postgresql.util.PSQLException
                 (as-system! (str "INSERT INTO sh_price_sheets (price_sheet__agreement_id, price_sheet__shop_id,"
                                  " price_sheet__partner_id, price_sheet__cents) VALUES (?, ?, ?, 1)")
                             acme-pine zinc pine))))
  (testing "the agreement moving to another partner takes its prices along"
    (as-system! "UPDATE sh_agreements SET agreement__partner_id = ? WHERE agreement__id = ?" zinc acme-pine)
    (is (= {:status :not-found} (call piet :prices/of-agreement {:agreement/id acme-pine})))
    (is (= {:status :ok :data [1200 1500]} (call olga :prices/of-agreement {:agreement/id acme-pine})))
    (as-system! "UPDATE sh_agreements SET agreement__partner_id = ? WHERE agreement__id = ?" pine acme-pine)
    (is (= {:status :ok :data [1200 1500]} (call piet :prices/of-agreement {:agreement/id acme-pine})))))

(deftest one-scope-from-the-parent-and-one-of-its-own
  (testing "a service on Acme's order, for Pine to do: the shop comes from the order"
    (is (uuid? (:data (call sam :services/add {:order/id acme-order :order-service/name "Blasting"
                                               :order-service/partner-id pine}))))
    (is (uuid? (:data (call sam :services/add {:order/id acme-order :order-service/name "Masking"
                                               :order-service/partner-id nil})))
        "a service nobody does yet: its own scope waits empty"))
  (testing "the order's shop sees both, the partner the one that names it, another shop neither"
    (is (= {:status :ok :data ["Blasting" "Masking"]} (call stella :services/mine {})))
    (is (= {:status :ok :data ["Blasting"]} (call piet :services/mine {})))
    (is (= {:status :ok :data ["Deep cleaning"]} (call olga :services/mine {}))))
  (testing "seeing the service gives nothing else: the partner doesn't see the order"
    (is (= {:status :not-found} (call piet :orders/note {:order/id acme-order})))
    (is (= {:status :ok :data "a"} (call stella :orders/note {:order/id acme-order})))))

(deftest the-start-checks-what-a-record-carries
  (let [check (fn [f] (:type (error #(rules/check (f shapes/description)))))
        start (fn [f] (error #(perm/start {:settings (f shapes/description) :db (gw/db *app*)})))]
    (testing "more than one scope on either side: the description says what is carried"
      (is (= ::rules/bad-description
             (check #(assoc-in % [:records :price-sheet :parent] [:agreement :price-sheet/agreement-id])))))
    (testing "a scope is carried from a scope of the same kind, a person from a person"
      (is (= ::rules/bad-description
             (check #(assoc-in % [:records :price-sheet :parent]
                               [:agreement :price-sheet/agreement-id {:price-sheet/shop-id :agreement/id}])))))
    (testing "a record under another carries at least one scope"
      (is (= ::rules/bad-description
             (check #(assoc-in % [:records :order-service :parent] [:order :order-service/order-id {}])))))
    (testing "the database must hold the matching link, for every column carried; the error carries the SQL"
      (sql! "DROP TABLE IF EXISTS sh_loose; CREATE TABLE sh_loose (loose__id uuid PRIMARY KEY, loose__agreement_id uuid NOT NULL,
               loose__shop_id uuid NOT NULL, loose__partner_id uuid NOT NULL,
               FOREIGN KEY (loose__agreement_id, loose__shop_id) REFERENCES sh_orders (order__id, order__shop_id))")
      (try
        (let [problem (start #(assoc-in % [:records :loose]
                                        {:table :sh_loose :id :loose/id
                                         :in [[:shop :loose/shop-id] [:shop :loose/partner-id]]
                                         :parent [:agreement :loose/agreement-id
                                                  {:loose/shop-id    :agreement/shop-id
                                                   :loose/partner-id :agreement/partner-id}]}))]
          (is (= ::perm/bad-description (:type problem)))
          (is (str/includes? (:sql problem) "ADD FOREIGN KEY (loose__agreement_id, loose__")))
        (finally
          (sql! "DROP TABLE IF EXISTS sh_loose")
          (perm/start {:settings shapes/description :db (gw/db *app*)}))))))

;; =============================================================================
;; A row can be a pass to one record
;; =============================================================================

(defn- in-wall! [person scopes sql & params]
  (perm/in-wall (gw/permissions *app*) {:person person :scopes scopes}
                (fn [tx] (jdbc/execute! (::pg/conn tx) (into [sql] params)))))

(deftest a-handoff-is-a-pass-to-one-order
  (testing "a handoff that is done gives nothing: Zinc's old one to Acme"
    (is (= {:status :not-found} (call stella :orders/note {:order/id zinc-order}))))
  (testing "before any handoff, the other shop's people see none of Acme's orders"
    (is (= {:status :not-found} (call petra :orders/note {:order/id acme-order})))
    (is (= {:status :ok :data []} (call petra :orders/incoming {}))))
  (let [handoff (:data (call sam :handoffs/give {:order/id acme-order :handoff/to-shop-id pine :handoff/driver-id nil}))]
    (is (uuid? handoff))
    (try
      (testing "the receiving shop's production sees that order, and its jobs"
        (is (= {:status :ok :data "a"} (call petra :orders/note {:order/id acme-order})))
        (is (= {:status :ok :data ["cut"]} (call petra :jobs/of-order {:order/id acme-order})))
        (is (= {:status :ok :data "cut"} (call petra :jobs/one {:job/id acme-job})))
        (is (= {:status :ok :data ["a"]} (call petra :orders/incoming {}))))
      (testing "a pass names one record: Acme's other order isn't there, with the right id"
        (is (= {:status :not-found} (call petra :orders/note {:order/id acme-order-2})))
        (is (= {:status :not-found} (call petra :jobs/one {:job/id acme-job-2})))
        (is (= {:status :not-found} (call petra :orders/of-shop {:shop/id acme})) "nor the shop as a whole"))
      (testing "the receiving shop's own roles decide who uses it"
        (is (= {:status :not-found} (call paulus :orders/note {:order/id acme-order})) "the bookkeeper doesn't")
        (is (= {:status :ok :data "a"} (call piet :orders/note {:order/id acme-order})) "its owner does"))
      (testing "nobody else"
        (is (= {:status :not-found} (call olga :orders/note {:order/id acme-order})))
        (is (= {:status :not-found} (call uma :orders/note {:order/id acme-order}))))
      (testing "a pass only reads: a write that declares the given action gets nothing from it"
        (is (= {:status :not-found} (call petra :orders/stamp {:order/id acme-order :order/note "mine"})))
        (is (= {:status :ok :data "a"} (call stella :orders/stamp {:order/id acme-order :order/note "a"}))))
      (testing "the receiver reports on the handoff itself; done, the order is gone for it, nothing to clean up"
        (is (= {:status :ok :data "done"} (call petra :handoffs/set-status {:handoff/id handoff :handoff/status "done"})))
        (is (= {:status :not-found} (call petra :orders/note {:order/id acme-order})))
        (is (= {:status :not-found} (call petra :jobs/one {:job/id acme-job})))
        (is (= {:status :ok :data []} (call petra :orders/incoming {}))))
      (testing "the receiver can't open it again: that would be writing itself a pass"
        (is (= :error (:status (call petra :handoffs/set-status {:handoff/id handoff :handoff/status "open"}))))
        (is (= {:status :not-found} (call petra :orders/note {:order/id acme-order}))))
      (testing "the giving shop can"
        (is (= {:status :ok :data "open"} (call stella :handoffs/set-status {:handoff/id handoff :handoff/status "open"})))
        (is (= {:status :ok :data "a"} (call petra :orders/note {:order/id acme-order}))))
      (finally
        (as-system! "DELETE FROM sh_handoffs WHERE handoff__id = ?" handoff)))))

(deftest a-scope-cant-write-itself-a-pass
  (testing "the receiving shop's person, behind their own shop's wall, storing a pass from Acme: refused"
    (is (thrown-with-msg? org.postgresql.util.PSQLException #"Only the giving scope may create a pass"
                          (in-wall! petra #{pine}
                                    (str "INSERT INTO sh_handoffs (handoff__order_id, handoff__from_shop_id,"
                                         " handoff__to_shop_id) VALUES (?, ?, ?)")
                                    acme-order-2 acme pine))))
  (testing "a pass 'from Pine' for Acme's order: the database ties the giver to the order's own shop"
    (is (thrown? org.postgresql.util.PSQLException
                 (as-system! (str "INSERT INTO sh_handoffs (handoff__order_id, handoff__from_shop_id,"
                                  " handoff__to_shop_id) VALUES (?, ?, ?)")
                             acme-order-2 pine pine))))
  (testing "nor through the endpoint: giving needs the giving action on the order"
    (is (= {:status :not-found}
           (call petra :handoffs/give {:order/id acme-order-2 :handoff/to-shop-id pine :handoff/driver-id nil}))))
  (testing "pointing an existing pass at another order, or at another receiver, is creating one"
    (let [handoff (:data (call sam :handoffs/give {:order/id acme-order :handoff/to-shop-id pine :handoff/driver-id nil}))]
      (try
        (is (thrown-with-msg? org.postgresql.util.PSQLException #"Only the giving scope may create a pass"
                              (in-wall! petra #{pine acme}
                                        "UPDATE sh_handoffs SET handoff__order_id = ? WHERE handoff__id = ?"
                                        acme-order-2 handoff)))
        (finally (as-system! "DELETE FROM sh_handoffs WHERE handoff__id = ?" handoff))))))

(deftest a-pass-to-one-person
  (let [handoff (:data (call sam :handoffs/give {:order/id acme-order :handoff/to-shop-id nil :handoff/driver-id uma}))]
    (try
      (is (= {:status :ok :data "a"} (call uma :orders/note {:order/id acme-order})) "the driver")
      (is (= {:status :not-found} (call uma :orders/note {:order/id acme-order-2})) "that order alone")
      (is (= {:status :not-found} (call uma :jobs/one {:job/id acme-job})) "and nothing under it: this pass lists none")
      (is (= {:status :not-found} (call petra :orders/note {:order/id acme-order})) "nobody else")
      (as-system! "UPDATE sh_handoffs SET handoff__status = 'done' WHERE handoff__id = ?" handoff)
      (is (= {:status :not-found} (call uma :orders/note {:order/id acme-order})))
      (finally (as-system! "DELETE FROM sh_handoffs WHERE handoff__id = ?" handoff)))))

(deftest the-start-checks-a-pass
  (let [pass  (first (:passes shapes/description))
        check (fn [f] (:type (error #(rules/check (update shapes/description :passes (fn [ps] [(f (first ps))]))))))]
    (is (= ::rules/bad-description (check #(assoc % :table :sh_packages)))
        "its table is a record kind in the giving and the receiving scope")
    (is (= ::rules/bad-description (check #(assoc % :under #{:message}))) "what comes with it hangs right under the record")
    (is (= ::rules/bad-description (check #(assoc % :grants {:catalogue/see :handoffs/work}))) "an open action isn't given")
    (is (= ::rules/bad-description (check #(assoc % :to-person :handoff/driver-id))) "one receiver")
    (testing "the database must tie the giver to the record's own scope; the error carries the SQL"
      (sql! "DROP TABLE IF EXISTS sh_loose_passes;
             CREATE TABLE sh_loose_passes (loose_pass__id uuid PRIMARY KEY, loose_pass__order_id uuid NOT NULL,
               loose_pass__from_id uuid NOT NULL, loose_pass__to_id uuid, loose_pass__status text NOT NULL)")
      (try
        (let [problem (error #(perm/start
                               {:settings (-> shapes/description
                                              (assoc-in [:records :loose-pass]
                                                        {:table :sh_loose_passes :id :loose-pass/id
                                                         :in [[:shop :loose-pass/from-id] [:shop :loose-pass/to-id]]})
                                              (update :passes conj
                                                      (assoc pass :table :sh_loose_passes
                                                             :record [:order :loose-pass/order-id]
                                                             :from [:shop :loose-pass/from-id]
                                                             :to [:shop :loose-pass/to-id]
                                                             :when [:= :loose-pass/status "open"])))
                                :db (gw/db *app*)}))]
          (is (= ::perm/bad-description (:type problem)))
          (is (str/includes? (str (:sql problem)) "ADD FOREIGN KEY (loose_pass__order_id, loose_pass__from_id)")))
        (finally
          (sql! "DROP TABLE IF EXISTS sh_loose_passes")
          (perm/start {:settings shapes/description :db (gw/db *app*)}))))))

(deftest a-pass-rings-who-holds-it
  (let [cookie   (sign-in petra)
        _        (ask! cookie "orders/incoming" {})
        line     (open-line cookie)
        incoming ["orders/incoming" {}]
        note     ["orders/note" {:order/id (str acme-order)}]]
    (try
      (watch! line incoming)
      (testing "a pass given to her shop: her list of incoming orders is asked again"
        (let [handoff (:data (call sam :handoffs/give {:order/id acme-order :handoff/to-shop-id pine
                                                       :handoff/driver-id nil}))]
          (try
            (is (= [incoming] (next-bell line 3000)))
            (ask! cookie "orders/note" {:order/id (str acme-order)})
            (watch! line incoming note)
            (testing "the order she holds a pass on changes: both her reads of it"
              (as-system! "UPDATE sh_orders SET order__note = 'a' WHERE order__id = ?" acme-order)
              (is (= #{incoming note} (set (next-bell line 3000)))))
            (testing "Acme's other order: her list hears of the shop it now reads from, her one order doesn't"
              (as-system! "UPDATE sh_orders SET order__note = 'b' WHERE order__id = ?" acme-order-2)
              (is (= [incoming] (next-bell line 3000))))
            (testing "the pass ends: both are asked again, and answer without the order"
              (as-system! "UPDATE sh_handoffs SET handoff__status = 'done' WHERE handoff__id = ?" handoff)
              (is (= #{incoming note} (set (next-bell line 3000))))
              (is (= "not-found" (:status (ask! cookie "orders/note" {:order/id (str acme-order)})))))
            (finally (as-system! "DELETE FROM sh_handoffs WHERE handoff__id = ?" handoff)))))
      (finally (close! line)))))

;; =============================================================================
;; A record can belong to a visitor
;; =============================================================================

(defn- post!
  "A POST from the app's own pages: [the response's first Set-Cookie pair or nil, its body]."
  [cookie path body]
  (let [r (.send client (-> (cond-> (-> (HttpRequest/newBuilder (URI. (str origin path)))
                                        (.header "Origin" origin)
                                        (.header "Content-Type" "application/json"))
                              cookie (.header "Cookie" cookie))
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            .build)
                 (HttpResponse$BodyHandlers/ofString))]
    [(some-> (.orElse (.firstValue (.headers r) "set-cookie") nil) (str/split #";") first)
     (.body r)]))

(defn- save-design!
  "Save a design over HTTP: [the cookie the answer set or nil, the design's id]."
  [cookie design-name]
  (let [[set-cookie body] (post! cookie "/parquery"
                                 (wire/write-json [{:endpoint "designs/save" :params {:design/name design-name}
                                                    :key (str (id))}]))]
    [set-cookie (parse-uuid (:data (first (wire/read-json body))))]))

(deftest a-visitors-record-is-theirs
  (let [[anna rim]   (save-design! nil "Rim")
        [bela frame] (save-design! nil "Frame")]
    (testing "saving without a session makes a visitor's session: its cookie goes out with the answer"
      (is (string? anna)) (is (uuid? rim))
      (is (not= anna bela)))
    (testing "a read makes no session: only the write that keeps something does"
      (let [[set-cookie _] (post! nil "/parquery" (wire/write-json [{:endpoint "designs/mine" :params {}}]))]
        (is (nil? set-cookie))))
    (testing "saving again in that session makes no new one"
      (let [[again _] (save-design! anna "Rim 2")]
        (is (nil? again))))
    (testing "each visitor sees their own, to read and to change"
      (is (= {:status "ok" :data ["Rim" "Rim 2"]} (ask! anna "designs/mine" {})))
      (is (= {:status "ok" :data ["Frame"]} (ask! bela "designs/mine" {})))
      (is (= {:status "ok" :data "Rim!"} (ask! anna "designs/rename" {:design/id (str rim) :design/name "Rim!"})))
      (is (= {:status "ok" :data "Rim!"} (ask! anna "designs/one" {:design/id (str rim)}))))
    (testing "and nobody else's: another visitor, a signed-in stranger, nobody at all"
      (is (= "not-found" (:status (ask! bela "designs/one" {:design/id (str rim)}))))
      (is (= "not-found" (:status (ask! bela "designs/rename" {:design/id (str rim) :design/name "mine now"}))))
      (is (= "not-found" (:status (ask! (sign-in uma) "designs/one" {:design/id (str rim)}))))
      (is (= "not-found" (:status (ask! (sign-in sam) "designs/one" {:design/id (str rim)}))) "a shop's owner neither")
      (is (= "not-found" (:status (ask! nil "designs/one" {:design/id (str rim)}))))
      (is (= {:status "ok" :data []} (ask! nil "designs/mine" {})) "someone with no session has no designs"))
    (testing "the visitor's id never comes from the request: an ask that names another visitor is still its sender's"
      (let [annas-visitor (:sh_designs/design__visitor_id
                           (first (as-system! "SELECT design__visitor_id FROM sh_designs WHERE design__id = ?" rim)))
            [_ body]      (post! bela "/parquery"
                                 (wire/write-json [{:endpoint "designs/mine" :params {}
                                                    :visitor (str annas-visitor) :person (str annas-visitor)}]))]
        (is (uuid? annas-visitor))
        (is (= {:status "ok" :data ["Frame"]} (first (wire/read-json body))))))
    (testing "the leak test asks as another visitor and as a signed-in stranger"
      (let [report (leaks/check (gw/endpoints *app*) {:person stella :foreign {:design rim}})]
        (is (= [] (:leaked report)))
        (is (every? (set (:checked report)) [:designs/mine :designs/one :designs/rename]))))
    (testing "at sign-in the visitor's records become the person's"
      (let [[tibor _] (post! anna (str "/login-as/" vic) "")]
        (is (string? tibor))
        (is (= #{"Rim 2" "Rim!"} (set (:data (ask! tibor "designs/mine" {})))) "signed in: still theirs")
        (is (= #{{:sh_designs/design__customer_id vic :sh_designs/design__visitor_id nil}}
               (set (as-system! "SELECT design__customer_id, design__visitor_id FROM sh_designs WHERE design__id = ?" rim)))
            "the person is set, the visitor emptied")
        (is (= {:status "ok" :data []} (ask! anna "designs/mine" {})) "the visitor's old cookie opens nothing")
        (is (= {:status "ok" :data "Rim!!"} (ask! tibor "designs/rename" {:design/id (str rim) :design/name "Rim!!"})))))
    (testing "when a visitor's session has ended, its records are the system's only"
      (as-system! "DELETE FROM groundwork.sessions WHERE session__person_id IS NULL")
      (is (= {:status "ok" :data []} (ask! bela "designs/mine" {})))
      (is (= "not-found" (:status (ask! bela "designs/one" {:design/id (str frame)}))))
      (is (= 1 (count (as-system! "SELECT 1 FROM sh_designs WHERE design__id = ?" frame))) "the row is still there"))))

(deftest a-visitor-hears-of-their-own-records
  (let [[anna rim] (save-design! nil "Bell")
        [bela _]   (save-design! nil "Other")
        _          (ask! anna "designs/mine" {})
        line       (open-line anna)
        mine       ["designs/mine" {}]]
    (try
      (watch! line mine)
      (testing "their own design changes"
        (as-system! "UPDATE sh_designs SET design__name = 'Bell 2' WHERE design__id = ?" rim)
        (is (= [mine] (next-bell line 3000))))
      (testing "another visitor saves one: nothing"
        (save-design! bela "Other 2")
        (is (nil? (next-bell line 500))))
      (finally (close! line)))))

(deftest the-start-checks-a-visitor-column
  (testing "one visitor column a kind, and one person column for the row to move to"
    (is (= ::rules/bad-description
           (:type (error #(rules/check (assoc-in shapes/description [:records :design :people]
                                                 {:designs/mine :design/customer-id
                                                  :orders/see   :design/shop-id}))))))
    (is (= ::rules/bad-description
           (:type (error #(rules/check (assoc-in shapes/description [:records :design :visitors]
                                                 {:designs/mine :design/visitor-id
                                                  :orders/see   :design/customer-id})))))))
  (testing "an open action isn't a visitor's"
    (is (= ::rules/bad-description
           (:type (error #(rules/check (assoc-in shapes/description [:records :design :visitors]
                                                 {:catalogue/see :design/visitor-id}))))))))

;; =============================================================================
;; A record between fixed sides carries them
;; =============================================================================

(deftest every-side-reads-and-writes
  (testing "the shop, the partner, the platform's staff and the customer each say something"
    (doseq [[person text] [[stella "1 shop"] [piet "2 partner"] [pat "3 staff"] [vic "4 customer"]]]
      (is (uuid? (:data (call person :talk/say {:conversation/id talk :message/text text}))) text)))
  (testing "each of them reads all of it"
    (doseq [person [stella piet pat vic]]
      (is (= {:status :ok :data ["1 shop" "2 partner" "3 staff" "4 customer"]}
             (call person :talk/messages {:conversation/id talk})))))
  (testing "nobody else: another shop's owner, a signed-in stranger, nobody"
    (is (= {:status :not-found} (call olga :talk/messages {:conversation/id talk})))
    (is (= {:status :not-found} (call uma :talk/messages {:conversation/id talk})))
    (is (= {:status :signed-out} (call nil :talk/messages {:conversation/id talk})))
    (is (= {:status :not-found} (call uma :talk/say {:conversation/id talk :message/text "let me in"}))))
  (testing "a message carries its conversation's sides, the person too: the SQL named none"
    (is (= #{{:sh_messages/message__shop_id acme :sh_messages/message__partner_id pine
              :sh_messages/message__staff_id shapes/platform :sh_messages/message__customer_id vic}}
           (set (as-system! (str "SELECT message__shop_id, message__partner_id, message__staff_id, message__customer_id"
                                 " FROM sh_messages WHERE message__conversation_id = ?")
                            talk))))))

(deftest a-person-is-carried-down-like-a-scope
  (is (uuid? (:data (call stella :talk/say {:conversation/id later-talk :message/text "anyone there?"}))))
  (testing "no customer on the conversation yet: Vic isn't in it"
    (is (= {:status :not-found} (call vic :talk/messages {:conversation/id later-talk}))))
  (testing "a customer named on the conversation later reaches the messages already under it"
    (as-system! "UPDATE sh_conversations SET conversation__customer_id = ? WHERE conversation__id = ?" vic later-talk)
    (is (= {:status :ok :data ["anyone there?"]} (call vic :talk/messages {:conversation/id later-talk}))))
  (testing "the conversation going to another person takes its messages along"
    (as-system! "UPDATE sh_conversations SET conversation__customer_id = ? WHERE conversation__id = ?" uma later-talk)
    (is (= {:status :not-found} (call vic :talk/messages {:conversation/id later-talk})))
    (is (= {:status :ok :data ["anyone there?"]} (call uma :talk/messages {:conversation/id later-talk}))))
  (testing "and nobody's again"
    (as-system! "UPDATE sh_conversations SET conversation__customer_id = NULL WHERE conversation__id = ?" later-talk)
    (is (= {:status :not-found} (call uma :talk/messages {:conversation/id later-talk})))
    (is (= [{:sh_messages/message__customer_id nil}]
           (as-system! "SELECT message__customer_id FROM sh_messages WHERE message__conversation_id = ?" later-talk))))
  (testing "the database refuses a message whose person isn't its conversation's"
    (is (thrown? org.postgresql.util.PSQLException
                 (as-system! (str "INSERT INTO sh_messages (message__conversation_id, message__customer_id, message__text)"
                                  " VALUES (?, ?, 'sneaky')")
                             later-talk uma))))
  (testing "a person is carried from a person, never from a scope"
    (is (= ::rules/bad-description
           (:type (error #(rules/check (assoc-in shapes/description [:records :message :parent 2 :message/customer-id]
                                                 :conversation/shop-id))))))))

;; =============================================================================
;; Shared rows have an owner
;; =============================================================================

(deftest a-read-brings-the-shops-rows-and-the-shared-ones
  (testing "nobody signed in: the shop's published packages and the platform's, no other shop's, no drafts"
    (is (= ["Gloss" "Shared Primer"] (names (call nil :catalogue/for-shop {:shop/id acme}))))
    (is (= ["Shared Primer" "Zinc Matte"] (names (call nil :catalogue/for-shop {:shop/id zinc})))))
  (testing "the platform's rows are an ordinary scope's: its people edit them as members, through the wall"
    (is (= "Shared Primer!" (get-in (call pat :catalogue/rename {:package/id shared :package/name "Shared Primer!"})
                                    [:data :package/name])))
    (is (= {:status :not-found} (call stella :catalogue/rename {:package/id shared :package/name "Mine"}))
        "Acme's staff don't")
    (as-system! "UPDATE sh_packages SET package__name = 'Shared Primer' WHERE package__id = ?" shared))
  (testing "an action people hold: both scopes must verify, or the ask is not found"
    (is (= {:status :not-found} (call stella :catalogue/workbench {:shop/id acme}))
        "Stella holds it in Acme, not in the platform's scope")
    (is (= {:status :not-found} (call pat :catalogue/workbench {:shop/id acme}))
        "the platform's owner doesn't hold it in Acme")
    (perm/grant-extra! (gw/permissions *app*) {:by pat :scope shapes/platform :person stella :action :catalogue/edit})
    (try
      (is (= ["Gloss" "Secret" "Shared Draft" "Shared Primer"]
             (names (call stella :catalogue/workbench {:shop/id acme})))
          "holding it in both: every row of both")
      (finally
        (perm/revoke-extra! (gw/permissions *app*)
                            {:by pat :scope shapes/platform :person stella :action :catalogue/edit}))))
  (testing "the fixed scope goes with a scope from the request, and an open action's must be of its kind"
    (let [start (fn [permission]
                  (error #(endpoints/start {:settings     (assoc (get-in (settings shapes/description {}) [:parquery])
                                                                 :endpoints [{:x/y {:read (fn [_ _] nil) :permission permission
                                                                                    :input [:map [:package/id :uuid]]
                                                                                    :answer :nil}}])
                                            :db           (gw/db *app*)
                                            :permissions  (gw/permissions *app*)
                                            :app-settings {}})))]
      (is (= :groundwork.settings/invalid
             (:type (start {:action :catalogue/see :record [:package :package/id] :and-scope [:shop shapes/platform]}))))
      (is (= ::endpoints/bad-endpoint
             (:type (start {:action :catalogue/see :scope [:shop shapes/platform] :and-scope [:shop shapes/platform]})))))))

(deftest a-watch-holds-both-scopes
  (ask! nil "catalogue/for-shop" {:shop/id (str acme)})
  (let [line (open-line nil)
        list ["catalogue/for-shop" {:shop/id (str acme)}]]
    (try
      (watch! line list)
      (testing "the shop's own package"
        (as-system! "UPDATE sh_packages SET package__name = 'Gloss' WHERE package__id = ?" gloss)
        (is (= [list] (next-bell line 3000))))
      (testing "a shared one: everyone showing a catalogue shows that row"
        (as-system! "UPDATE sh_packages SET package__name = 'Shared Primer' WHERE package__id = ?" shared)
        (is (= [list] (next-bell line 3000))))
      (testing "another shop's: nothing"
        (as-system! "UPDATE sh_packages SET package__name = 'Zinc Matte' WHERE package__id = ?" zinc-matte)
        (is (nil? (next-bell line 500))))
      (finally (close! line)))))

(deftest a-visitor-hears-its-shops-bell
  (ask! nil "catalogue/of-shop" {:shop/id (str acme)})
  (ask! nil "catalogue/busy?" {:shop/id (str acme)})
  (let [line (open-line nil)
        list ["catalogue/of-shop" {:shop/id (str acme)}]
        busy ["catalogue/busy?" {:shop/id (str acme)}]]
    (try
      (watch! line list busy)
      (testing "a package of that shop changes: the visitor's list is asked again"
        (as-system! "UPDATE sh_packages SET package__name = 'Gloss' WHERE package__id = ?" gloss)
        (is (= [list] (next-bell line 3000))))
      (testing "another shop's package: nothing"
        (as-system! "UPDATE sh_packages SET package__name = 'Zinc Matte' WHERE package__id = ?" zinc-matte)
        (is (nil? (next-bell line 500))))
      (testing "an order of that shop, a kind the open action doesn't reach: nothing, though the read's SQL names the table"
        (as-system! "UPDATE sh_orders SET order__note = 'a' WHERE order__id = ?" acme-order)
        (is (nil? (next-bell line 500))))
      (finally (close! line)))))
