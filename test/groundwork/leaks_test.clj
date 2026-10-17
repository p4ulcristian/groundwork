(ns groundwork.leaks-test
  "The leak test: sealed endpoints pass; a via whose condition forgot the
   status lets another shop's notes out, and the test says which."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.endpoints :as endpoints]
   [groundwork.leaks :as leaks]
   [groundwork.migrations :as migrations]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [db-settings sql!]])
  (:import
   (java.util UUID)))

(defn- id [] (UUID/randomUUID))

(def acme (id)) (def zinc (id)) (def partner (id))
(def paul (id)) (def peter (id)) (def ana (id)) (def pat (id)) (def pia (id))
(def acme-note (id)) (def zinc-note (id)) (def acme-board (id)) (def zinc-board (id))

(defn- description [link-condition]
  {:scopes  {:shop    {:actions {:notes/see #{:read} :notes/move #{:read :update}}
                       :roles   {"Production" #{:notes/see :notes/move}} :parent nil}
             :partner {:actions {:links/see #{:read}} :roles {"Member" #{:links/see}} :parent nil}}
   :records {:note         {:table :notes :id :note/id :whose {:note/shop-id :shop}}
             :board        {:table :boards :id :board/id :whose {:board/shop-id :shop}}
             :partner-link {:table :partner_links :id :partner-link/id
                            :whose {:partner-link/shop-id :shop :partner-link/partner-id :partner}}}
   :rules   [{:allow #{:notes/see :notes/move} :on :note :to [:member :note/shop-id]}
             {:allow #{:notes/see :notes/move} :on :board :to [:member :board/shop-id]}
             ;; a partner sees a shop's notes while their link holds
             {:allow :notes/see :on :note
              :via   {:table :partner_links :match {:note/shop-id :partner-link/shop-id}
                      :to [:member :partner-link/partner-id :links/see] :when link-condition}}
             {:allow :links/see :on :partner-link :to [:member :partner-link/partner-id]}]})

(def sealed (description [:= :partner-link/status "active"]))
(def broken (description [:not-null :partner-link/status]))

(pg/defsql lk-notes-of-shop)
(pg/defsql lk-note)
(pg/defsql lk-move-note)

(def endpoint-map
  {:notes/of-shop {:read       (fn [{:keys [db]} params] (mapv :note/id (pg/rows db lk-notes-of-shop params)))
                   :permission {:action :notes/see :scope [:shop :shop/id]}
                   :input      [:map [:shop/id :uuid]]
                   :answer     [:vector :uuid]}
   :notes/one     {:read       (fn [{:keys [db]} params] (:note/id (pg/row db lk-note params)))
                   :permission {:action :notes/see :record [:note :note/id]}
                   :input      [:map [:note/id :uuid]]
                   :answer     [:maybe :uuid]}
   :notes/move    {:write      (fn [{:keys [db]} params] (:note/id (pg/row db lk-move-note params)))
                   :permission {:action :notes/move :record [:note :note/id]}
                   :input      [:map [:note/id :uuid] [:board/id {:groundwork/record :board} :uuid]]
                   :answer     [:maybe :uuid]}
   :notes/mine    {:read       (fn [_ _] [])
                   :permission {:action :notes/see :list :note}
                   :input      [:map]
                   :answer     [:vector :uuid]}
   :zinc/notice   {:read       (fn [_ _] "for zinc")
                   :permission {:action :notes/see :scope [:shop zinc]}
                   :input      [:map {:closed true}]
                   :answer     :string}
   :site/hello    {:read       (fn [_ _] "hello")
                   :permission :public
                   :input      [:map]
                   :answer     :string}})

(def settings {:endpoints [endpoint-map] :max-asks 50 :max-running 4 :bearer nil :on-answer nil
               :bells {:global {} :personal {} :silent {:groundwork/every-other-table "the test database is shared by every test"}}})

(def ^:dynamic *db* nil)

(defn- part [d]
  (let [perms (perm/start {:settings d :db *db*})]
    (endpoints/start {:settings settings :db *db* :permissions perms :app-settings {}})))

(defn- join! [perms by scope person role]
  (let [email (str person "@test.example")
        token (perm/invite! perms {:by by :scope scope :email email :role role :days 7})]
    (perm/accept-invitation! perms {:token token :person person :email email})))

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS notes, boards, partner_links;
           CREATE TABLE notes (note__id uuid PRIMARY KEY, note__shop_id uuid NOT NULL, note__board_id uuid);
           CREATE INDEX ON notes (note__shop_id);
           CREATE TABLE boards (board__id uuid PRIMARY KEY, board__shop_id uuid NOT NULL);
           CREATE TABLE partner_links (partner_link__partner_id uuid NOT NULL, partner_link__shop_id uuid NOT NULL,
                                       partner_link__status text NOT NULL,
                                       partner_link__id uuid PRIMARY KEY DEFAULT gen_random_uuid());
           CREATE INDEX ON partner_links (partner_link__shop_id);
           CREATE INDEX ON partner_links (partner_link__partner_id)")
    (sql! "INSERT INTO notes VALUES (?, ?, NULL), (?, ?, NULL); INSERT INTO boards VALUES (?, ?), (?, ?);
           INSERT INTO partner_links VALUES (?, ?, 'ended')"
          acme-note acme zinc-note zinc acme-board acme zinc-board zinc partner zinc)
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
          (sql! "DROP TABLE IF EXISTS notes, boards, partner_links"))))))

(def foreign {:shop zinc :note zinc-note :board zinc-board})

(deftest sealed-endpoints-pass
  (let [report (leaks/check (part sealed) {:person peter :foreign foreign})]
    (is (= [] (:leaked report)))
    (is (= [:notes/mine :notes/move :notes/of-shop :notes/one :zinc/notice] (:checked report))
        "a fixed scope too: Peter isn't Zinc's, and there is no param to set")
    (is (= #{:site/hello :groundwork/grants} (set (keys (:skipped report)))))
    (testing "the partner's member, through an ended link: nothing"
      (is (= [] (:leaked (leaks/check (part sealed) {:person pia :foreign foreign})))))))

(deftest a-condition-that-forgot-the-status-is-caught
  (let [report (leaks/check (part broken) {:person pia :foreign foreign})]
    (is (= #{:notes/of-shop :notes/one :zinc/notice} (set (keep :endpoint (:leaked report))))
        "the ended link still lets Zinc's notes out, and Zinc's own area")
    (is (= [] (filter :wall (:leaked report)))
        "the wall shows what the sentences give: the test proves they match, not that the sentence is right")
    (is (= :ok (:status (:answer (first (:leaked report))))))))

(deftest a-scope-endpoint-honours-a-via-like-a-record-one
  (sql! "SET groundwork.bypass = 'on'; INSERT INTO partner_links VALUES (?, ?, 'active')" partner acme)
  (try
    (let [p (part sealed)]
      (is (= {:status :ok :data [acme-note]}
             (endpoints/call p {:endpoint :notes/of-shop :params {:shop/id acme} :person pia :flags #{}}))
          "an active link gives the partner's member Acme's notes, as for one note")
      (is (= {:status :ok :data acme-note}
             (endpoints/call p {:endpoint :notes/one :params {:note/id acme-note} :person pia :flags #{}}))))
    (finally (sql! "SET groundwork.bypass = 'on'; DELETE FROM partner_links WHERE partner_link__shop_id = ?" acme))))
