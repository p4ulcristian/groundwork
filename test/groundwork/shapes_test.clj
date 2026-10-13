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

(def ^:dynamic *app* nil)

(defn- settings [description endpoint-map]
  (-> app-settings
      (assoc :permissions description)
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
           acme-order acme vic zinc-order zinc))

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
          (join! perms sam acme stella "Staff")
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
    (is (= {:status :ok :data 1} (call stella :catalogue/busy? {:shop/id acme}))))
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
                                  :foreign {:shop zinc :package zinc-secret :order zinc-order}
                                  :sealed  sealed})]
    (is (= [] (:leaked report)))
    (is (every? (set (:checked report)) [:catalogue/of-shop :catalogue/package :catalogue/busy? :shop/card
                                         :catalogue/rename :orders/of-shop]))
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
