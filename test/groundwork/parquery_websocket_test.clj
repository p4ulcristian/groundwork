(ns groundwork.parquery-websocket-test
  "The bells: Postgres rings after a commit, and each open line hears only
   about the reads it shows, in scopes it may see."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.endpoints :as endpoints]
   [groundwork.parquery.websocket :as websocket]
   [groundwork.parquery.wire :as wire]
   [groundwork.sessions :as sessions]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [app-settings sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket
                  WebSocket$Listener)
   (java.util UUID)
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def acme (UUID/randomUUID))
(def zinc (UUID/randomUUID))
(def paul (UUID/randomUUID))
(def ana (UUID/randomUUID))
(def note-1 (UUID/randomUUID))
(def note-2 (UUID/randomUUID))
(def zinc-note (UUID/randomUUID))

(def description
  {:scopes         {:shop {:actions #{:notes/see :notes/add} :roles {} :parent nil}}
   :records        {:note {:table :notes :id :note/id :in [:shop :note/shop-id]}
                    :tag  {:table :tags :id :tag/id :in [:shop :tag/shop-id]}}
   :links          []
   :session-blocks {}})

(pg/defsql ws-notes-of-shop)
(pg/defsql ws-one-note)
(pg/defsql ws-add-note)

(def note-shape [:map [:note/id :uuid] [:note/text :string]])

(def endpoints-map
  {:notes/of-shop {:read       (fn [{:keys [db]} params] (pg/rows db ws-notes-of-shop params))
                   :permission {:action :notes/see :scope [:shop :shop/id]}
                   :input      [:map [:shop/id :uuid]]
                   :answer     [:vector note-shape]}
   :notes/one     {:read       (fn [{:keys [db]} params] (pg/row db ws-one-note params))
                   :permission {:action :notes/see :record [:note :note/id]}
                   :input      [:map [:note/id :uuid]]
                   :answer     [:maybe note-shape]}
   :notes/add     {:write      (fn [{:keys [db]} params] (pg/row db ws-add-note params))
                   :permission {:action :notes/add :scope [:shop :shop/id]}
                   :input      [:map [:shop/id :uuid] [:note/id {:groundwork/record false} :uuid] [:note/text :string]]
                   :answer     note-shape}})

(def ^:dynamic *app* nil)

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS notes, tags;
           CREATE TABLE notes (note__id uuid PRIMARY KEY, note__shop_id uuid NOT NULL, note__text text NOT NULL);
           CREATE TABLE tags (tag__id uuid PRIMARY KEY, tag__shop_id uuid NOT NULL)")
    (sql! "INSERT INTO notes VALUES (?, ?, 'one'), (?, ?, 'two'), (?, ?, 'zinc')"
          note-1 acme note-2 acme zinc-note zinc)
    (let [app (gw/start (-> app-settings
                            (assoc :permissions description)
                            (assoc-in [:parquery :endpoints] [endpoints-map])))]
      (try
        (perm/create-scope! (gw/permissions app) {:kind :shop :id acme :parent nil :owner paul})
        (perm/create-scope! (gw/permissions app) {:kind :shop :id zinc :parent nil :owner ana})
        (binding [*app* app] (t))
        (finally
          (gw/stop app)
          (sql! "DROP TABLE IF EXISTS notes, tags"))))))

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
  ([cookie endpoint params] (ask! cookie endpoint params nil))
  ([cookie endpoint params watching]
   (let [r (.send client (-> (HttpRequest/newBuilder (URI. (str origin "/parquery")))
                             (.header "Origin" origin) (.header "Cookie" cookie)
                             (.header "Content-Type" "application/json")
                             (.POST (HttpRequest$BodyPublishers/ofString
                                     (wire/write-json [(cond-> {:endpoint endpoint :params params :key (str (UUID/randomUUID))}
                                                         watching (assoc :watching watching))])))
                             .build)
                  (HttpResponse$BodyHandlers/ofString))]
     (first (wire/read-json (.body r))))))

(defn- open-line
  "A websocket to /parquery; its messages go to a queue."
  [cookie & {:keys [line-origin] :or {line-origin origin}}]
  (let [messages (LinkedBlockingQueue.)
        listener (reify WebSocket$Listener
                   (onText [_ ws data _last]
                     (.add messages (str data))
                     (.request ws 1)
                     nil)
                   (onClose [_ _ws _code _reason]
                     (.add messages "closed")
                     nil))
        ws       (.get (.buildAsync (cond-> (.header (.newWebSocketBuilder client) "Origin" line-origin)
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

(defn- next-message [{:keys [^LinkedBlockingQueue messages]} ms]
  (some-> (.poll messages ms TimeUnit/MILLISECONDS) wire/read-json))

(defn- close! [{:keys [^WebSocket ws]}]
  (when-not (.isOutputClosed ws)
    (try (.get (.sendClose ws WebSocket/NORMAL_CLOSURE "") 5 TimeUnit/SECONDS)
         (catch Exception _ nil))))

(defn- closed-within? [{:keys [^LinkedBlockingQueue messages]} ms]
  (loop [deadline (+ (System/currentTimeMillis) ms)]
    (let [left (- deadline (System/currentTimeMillis))
          m    (when (pos? left) (.poll messages left TimeUnit/MILLISECONDS))]
      (cond (= "closed" m) true
            (nil? m)       false
            :else          (recur deadline)))))

(defn- expire! [person]
  (sql! "UPDATE groundwork.sessions SET session__expires_at = now() - interval '1 second'
         WHERE session__person_id = ?" person))

(defn- as-system! [sql & params]
  (perm/as-system (gw/permissions *app*) (fn [tx] (jdbc/execute! (::pg/conn tx) (into [sql] params)))))

(deftest a-change-rings-who-shows-it
  (let [paul-cookie (sign-in paul)
        ana-cookie  (sign-in ana)
        _           (ask! paul-cookie "notes/of-shop" {:shop/id (str acme)})
        _           (ask! ana-cookie "notes/of-shop" {:shop/id (str zinc)})
        paul-line   (open-line paul-cookie)
        ana-line    (open-line ana-cookie)
        acme-list   ["notes/of-shop" {:shop/id (str acme)}]]
    (try
      (watch! paul-line acme-list)
      (watch! ana-line ["notes/of-shop" {:shop/id (str zinc)}])
      (testing "a write in Acme rings Acme's list, not Zinc's"
        (as-system! "INSERT INTO notes VALUES (?, ?, 'three')" (UUID/randomUUID) acme)
        (is (= [acme-list] (next-bell paul-line 3000)))
        (is (nil? (next-bell ana-line 500))))
      (testing "a rolled-back write rings nothing"
        (try (perm/as-system (gw/permissions *app*)
                             (fn [tx]
                               (jdbc/execute! (::pg/conn tx) ["INSERT INTO notes VALUES (?, ?, 'never')" (UUID/randomUUID) acme])
                               (throw (ex-info "roll back" {}))))
             (catch Exception _))
        (is (nil? (next-bell paul-line 700))))
      (testing "another table of the same shop rings nothing: the read doesn't use it"
        (as-system! "INSERT INTO tags VALUES (?, ?)" (UUID/randomUUID) acme)
        (is (nil? (next-bell paul-line 700))))
      (testing "a write through parquery rings too"
        (is (= "ok" (:status (ask! paul-cookie "notes/add" {:shop/id (str acme) :note/id (str (UUID/randomUUID))
                                                            :note/text "four"}))))
        (is (= [acme-list] (next-bell paul-line 3000))))
      (finally (close! paul-line) (close! ana-line)))))

(deftest a-one-record-read-rings-for-its-record
  (let [cookie (sign-in paul)
        _      (ask! cookie "notes/one" {:note/id (str note-1)})
        _      (ask! cookie "notes/of-shop" {:shop/id (str acme)})
        line   (open-line cookie)
        one    ["notes/one" {:note/id (str note-1)}]
        all    ["notes/of-shop" {:shop/id (str acme)}]]
    (try
      (watch! line one all)
      (as-system! "UPDATE notes SET note__text = 'two!' WHERE note__id = ?" note-2)
      (is (= [all] (next-bell line 3000)) "another note: the list, not the one-note read")
      (as-system! "UPDATE notes SET note__text = 'one!' WHERE note__id = ?" note-1)
      (is (= #{one all} (set (next-bell line 3000))))
      (finally (close! line)))))

(deftest a-watch-is-checked-like-an-ask
  (let [cookie (sign-in ana)
        line   (open-line cookie)]
    (try
      (watch! line ["notes/of-shop" {:shop/id (str acme)}])
      (as-system! "INSERT INTO notes VALUES (?, ?, 'five')" (UUID/randomUUID) acme)
      (is (nil? (next-bell line 700)) "Ana may not see Acme's notes, so she hears nothing about them")
      (finally (close! line)))))

(deftest another-sites-page-cant-listen
  (is (thrown? Exception (open-line (sign-in paul) :line-origin "https://evil.example"))))

(deftest a-read-not-run-yet-rings-on-its-scope
  (is (empty? (endpoints/files-of (gw/endpoints *app*) :notes/unknown)))
  (let [cookie (sign-in paul)
        line   (open-line cookie)]
    (try
      (sql! "DELETE FROM groundwork.endpoint_files WHERE endpoint_file__endpoint = 'notes/one'")
      (reset! (:known-files (gw/endpoints *app*)) {})
      (watch! line ["notes/one" {:note/id (str note-2)}])
      (as-system! "INSERT INTO tags VALUES (?, ?)" (UUID/randomUUID) acme)
      (is (= [["notes/one" {:note/id (str note-2)}]] (next-bell line 3000))
          "its tables aren't known, so any change in its scope rings it")
      (finally (close! line)))))

(deftest a-line-ends-with-its-session
  (testing "signed out everywhere: the line closes, on every server, by the session's own bell"
    (let [line (open-line (sign-in paul))]
      (try
        (sessions/sign-out-everywhere! (gw/part *app* :groundwork/sessions) paul)
        (is (closed-within? line 3000))
        (finally (close! line)))))
  (testing "an expired session: the next watch message closes the line"
    (let [line (open-line (sign-in ana))]
      (try
        (expire! ana)
        (watch! line ["notes/of-shop" {:shop/id (str zinc)}])
        (is (closed-within? line 3000))
        (finally (close! line)))))
  (testing "an expired session: the sweep closes a quiet line"
    (let [line (open-line (sign-in ana))]
      (try
        (expire! ana)
        (websocket/recheck! (get-in (gw/part *app* :groundwork/parquery) [:bells]))
        (is (closed-within? line 3000))
        (finally (close! line)))))
  (testing "a line that stays: its session is live"
    (let [cookie (sign-in paul)
          _      (ask! cookie "notes/of-shop" {:shop/id (str acme)})
          line   (open-line cookie)]
      (try
        (watch! line ["notes/of-shop" {:shop/id (str acme)}])
        ;; the server takes the watch on its own thread: wait until it holds it
        (let [lines (get-in (gw/part *app* :groundwork/parquery) [:bells :lines])]
          (loop [n 0]
            (when (and (< n 100) (not-any? (comp seq :watches) (vals @lines)))
              (Thread/sleep 50)
              (recur (inc n)))))
        (websocket/recheck! (get-in (gw/part *app* :groundwork/parquery) [:bells]))
        (as-system! "INSERT INTO notes VALUES (?, ?, 'six')" (UUID/randomUUID) acme)
        (is (= [["notes/of-shop" {:shop/id (str acme)}]] (next-bell line 3000)))
        (finally (close! line))))))

(deftest the-sweep-drops-a-watch-no-longer-allowed
  (let [nina   (UUID/randomUUID)
        perms  (gw/permissions *app*)
        email  (str nina "@test.example")
        _      (perm/create-role! perms {:by paul :scope acme :role "Reader" :actions #{:notes/see}})
        token  (perm/invite! perms {:by paul :scope acme :email email :role "Reader" :days 7})
        _      (perm/accept-invitation! perms {:token token :person nina :email email})
        cookie (sign-in nina)
        _      (ask! cookie "notes/of-shop" {:shop/id (str acme)})
        line   (open-line cookie)
        list   ["notes/of-shop" {:shop/id (str acme)}]]
    (try
      (watch! line list)
      (as-system! "INSERT INTO notes VALUES (?, ?, 'seven')" (UUID/randomUUID) acme)
      (is (= [list] (next-bell line 3000)) "a member hears about the shop's notes")
      (perm/remove-member! perms {:by paul :scope acme :person nina})
      (websocket/recheck! (get-in (gw/part *app* :groundwork/parquery) [:bells]))
      (as-system! "INSERT INTO notes VALUES (?, ?, 'eight')" (UUID/randomUUID) acme)
      (is (nil? (next-bell line 700)) "removed from the shop, she hears nothing more")
      (finally (close! line)))))

(deftest a-writes-answer-and-its-bell-carry-one-tag
  (let [cookie    (sign-in paul)
        _         (ask! cookie "notes/of-shop" {:shop/id (str acme)})
        line      (open-line cookie)
        acme-list ["notes/of-shop" {:shop/id (str acme)}]
        zinc-list ["notes/of-shop" {:shop/id (str zinc)}]]
    (try
      (watch! line acme-list)
      (let [answer (ask! cookie "notes/add" {:shop/id (str acme) :note/id (str (UUID/randomUUID)) :note/text "tagged"}
                         [acme-list zinc-list ["notes/one" {:note/id (str note-1)}]])]
        (testing "the answer says which of the reads on screen the write changed"
          (is (= "ok" (:status answer)))
          (is (= [acme-list] (:changed answer)) "not another shop's list, not another note")
          (is (string? (:tag answer))))
        (testing "the websocket's bell for the same write carries the same tag"
          (is (= {:changed [acme-list] :tag (:tag answer)} (next-message line 3000)))))
      (testing "a change from elsewhere has no tag"
        (as-system! "INSERT INTO notes VALUES (?, ?, 'untagged')" (UUID/randomUUID) acme)
        (is (= {:changed [acme-list]} (next-message line 3000))))
      (testing "a write that names no reads gets a plain answer"
        (is (= #{:status :data} (set (keys (ask! cookie "notes/add" {:shop/id (str acme) :note/id (str (UUID/randomUUID))
                                                                    :note/text "plain"}))))))
      (finally (close! line)))))

(deftest the-team-tables-ring
  (let [perms  (gw/permissions *app*)
        kim    (UUID/randomUUID)
        lee    (UUID/randomUUID)
        cookie (sign-in kim)
        line   (open-line cookie)
        grants ["groundwork/grants" {}]]
    (try
      (ask! cookie "groundwork/grants" {})
      (watch! line grants)
      (testing "an extra action for the person rings their grants"
        (perm/grant-extra! perms {:by paul :scope acme :person kim :action :notes/see})
        (is (= [grants] (next-bell line 3000))))
      (testing "a role's actions changing rings the people who hold the role"
        (perm/create-role! perms {:by paul :scope acme :role "Bell reader" :actions #{}})
        (perm/as-system perms (fn [tx] (jdbc/execute! (::pg/conn tx)
                                                      ["INSERT INTO groundwork.memberships
                                                        SELECT ?, ?, role__id FROM groundwork.roles
                                                        WHERE role__scope_id = ? AND role__name = 'Bell reader'"
                                                       kim acme acme])))
        (is (= [grants] (next-bell line 3000)) "joining the scope")
        (perm/set-role-actions! perms {:by paul :scope acme :role "Bell reader" :actions #{:notes/add}})
        (is (= [grants] (next-bell line 3000))))
      (testing "someone else's change rings nothing here"
        (perm/grant-extra! perms {:by ana :scope zinc :person lee :action :notes/see})
        (is (nil? (next-bell line 700))))
      (finally (close! line)))))
