(ns groundwork.repl-test
  "Talking to a running app: asking as a person, why a person may or may
   not, the peeks, and the development start."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.repl :as repl]
   [jsonista.core :as json]
   [groundwork.sessions :as sessions]
   [groundwork.test-support :refer [app-settings sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util UUID)))

(defn- id [] (UUID/randomUUID))

(def acme (id)) (def zinc (id)) (def partner (id))
(def paul (id)) (def peter (id)) (def ana (id)) (def pat (id)) (def pia (id)) (def kati (id))
(def acme-open (id)) (def acme-closed (id)) (def zinc-katis (id))

(def description
  {:scopes  {:shop    {:actions #{:orders/see :orders/edit :money/move}
                       :roles   {"Production" #{:orders/see}}
                       :parent  nil}
             :partner {:actions #{:agreements/see} :roles {"Member" #{:agreements/see}} :parent nil}}
   :records {:order {:table :rpl_orders :id :rpl-order/id :in [:shop :rpl-order/shop-id]
                     :people {:orders/see :rpl-order/customer-id}
                     :only-when {:orders/edit [:= :rpl-order/status "open"]}}}
   :links   [{:table  :rpl_agreements
              :from   [:partner :rpl-agreement/partner-id]
              :to     [:shop :rpl-agreement/shop-id]
              :when   [:= :rpl-agreement/status "active"]
              :grants {:orders/see :agreements/see}}]
   :session-blocks {:impersonated #{:money/move}}})

(pg/defsql rpl-orders-of-shop)

(def endpoint-map
  {:orders/of-shop {:read       (fn [{:keys [db]} params] (mapv :rpl-order/id (pg/rows db rpl-orders-of-shop params)))
                    :permission {:action :orders/see :scope [:shop :shop/id]}
                    :input      [:map [:shop/id :uuid]]
                    :answer     [:vector :uuid]}})

(def settings (-> app-settings
                  (assoc :permissions description)
                  (assoc-in [:parquery :endpoints] [endpoint-map])))

(def ^:dynamic *app* nil)

(defn- join! [perms by scope person role]
  (let [email (str person "@test.example")
        token (perm/invite! perms {:by by :scope scope :email email :role role :days 7})]
    (perm/accept-invitation! perms {:token token :person person :email email})))

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS rpl_orders, rpl_agreements;
           CREATE TABLE rpl_orders (rpl_order__id uuid PRIMARY KEY, rpl_order__shop_id uuid NOT NULL,
                                    rpl_order__customer_id uuid, rpl_order__status text NOT NULL);
           CREATE TABLE rpl_agreements (rpl_agreement__partner_id uuid NOT NULL, rpl_agreement__shop_id uuid NOT NULL,
                                        rpl_agreement__status text NOT NULL)")
    (sql! "INSERT INTO rpl_orders VALUES (?, ?, NULL, 'open'), (?, ?, NULL, 'closed'), (?, ?, ?, 'open');
           INSERT INTO rpl_agreements VALUES (?, ?, 'active')"
          acme-open acme acme-closed acme zinc-katis zinc kati partner acme)
    (let [app (gw/start settings)]
      (try
        (let [perms (gw/permissions app)]
          (perm/create-scope! perms {:kind :shop :id acme :parent nil :owner paul})
          (perm/create-scope! perms {:kind :shop :id zinc :parent nil :owner ana})
          (perm/create-scope! perms {:kind :partner :id partner :parent nil :owner pat})
          (join! perms paul acme peter "Production")
          (join! perms pat partner pia "Member"))
        (binding [*app* app] (t))
        (finally
          (gw/stop app)
          (sql! "DROP TABLE IF EXISTS rpl_orders, rpl_agreements"))))))

(defn- says? [{:keys [because]} part]
  (boolean (some #(str/includes? % part) because)))

(deftest asking-as-a-person
  (is (= {:status :ok :data #{acme-open acme-closed}}
         (update (repl/ask *app* :orders/of-shop {:shop/id acme} :as peter) :data set)))
  (is (= {:status :not-found} (repl/ask *app* :orders/of-shop {:shop/id zinc} :as peter)))
  (is (= :signed-out (:status (repl/ask *app* :orders/of-shop {:shop/id acme})))))

(deftest why-in-plain-words
  (testing "an owner"
    (let [w (repl/why *app* paul :orders/edit [:order acme-open])]
      (is (:allowed? w))
      (is (says? w (str "is in shop " acme)))
      (is (says? w "an owner holds everything"))))
  (testing "a role without the action"
    (let [w (repl/why *app* peter :orders/edit [:order acme-open])]
      (is (not (:allowed? w)))
      (is (says? w "is Production in shop"))
      (is (says? w "doesn't hold :orders/edit"))))
  (testing "another shop's record"
    (let [w (repl/why *app* peter :orders/see [:order zinc-katis])]
      (is (not (:allowed? w)))
      (is (says? w (str "has no membership in shop " zinc " or above")))))
  (testing "through a link"
    (let [w (repl/why *app* pia :orders/see [:order acme-open])]
      (is (:allowed? w))
      (is (says? w "A link through rpl_agreements gives :orders/see"))))
  (testing "a customer on their order"
    (let [w (repl/why *app* kati :orders/see [:order zinc-katis])]
      (is (:allowed? w))
      (is (says? w "it's theirs"))))
  (testing "an only-when that doesn't hold"
    (let [w (repl/why *app* paul :orders/edit [:order acme-closed])]
      (is (not (:allowed? w)))
      (is (says? w "it doesn't hold now, so nobody may"))))
  (testing "a session flag that blocks"
    (let [w (repl/why *app* paul :money/move [:order acme-open] :flags #{:impersonated})]
      (is (not (:allowed? w)))
      (is (says? w "The session is impersonated, which blocks :money/move"))))
  (testing "in a scope"
    (is (:allowed? (repl/why *app* peter :orders/see acme)))
    (is (not (:allowed? (repl/why *app* peter :orders/see zinc)))))
  (testing "a record that isn't there"
    (is (says? (repl/why *app* paul :orders/see [:order (id)]) "There's no order"))))

(deftest the-peeks
  (testing "no lines open"
    (is (= [] (repl/lines *app*))))
  (testing "a bell heard"
    (perm/as-system (gw/permissions *app*)
                    (fn [tx] (jdbc/execute! (::pg/conn tx) ["UPDATE rpl_orders SET rpl_order__status = 'open' WHERE rpl_order__id = ?"
                                                            acme-open])))
    (is (loop [n 30]
          (cond (some #(= "rpl_orders" (get-in % [:bell :t])) (repl/recent-bells *app*)) true
                (zero? n) false
                :else (do (Thread/sleep 100) (recur (dec n)))))))
  (testing "a person's sessions, and ending them"
    (let [part (gw/part *app* :groundwork/sessions)]
      (sessions/sign-in {::sessions/sessions part :headers {}} {} peter)
      (is (= 1 (count (repl/sessions *app* peter))))
      (repl/sign-out-everywhere! *app* peter)
      (is (= [] (repl/sessions *app* peter)))))
  (testing "migrations: all ran, none waiting or changed"
    (let [{:keys [ran waiting changed missing]} (repl/migrations *app*)]
      (is (some #(= {:source "groundwork" :file "001_sessions.sql"} (select-keys % [:source :file])) ran))
      (is (= [] waiting changed missing)))))

(deftest the-development-start
  (testing "only in development"
    (is (= ::repl/not-development
           (:type (ex-data (try (repl/start! settings {}) (catch Exception e e)))))))
  (testing "the app, an nREPL and a log, from one call"
    (let [dir (str (Files/createTempDirectory "groundwork-dev" (make-array FileAttribute 0)))
          dev (repl/start! (-> settings (assoc :environment :development) (assoc-in [:server :port] 0))
                           {:dir dir})]
      (try
        (is (pos-int? (gw/port (:app dev))))
        (is (.exists (io/file dir ".nrepl-port")))
        (is (= (str (:port (:nrepl dev))) (slurp (io/file dir ".nrepl-port"))))
        (testing "clojure-mcp is pointed at the nREPL in .mcp.json"
          (is (= ["-Tmcp" "start" ":config-profile" ":repl" ":port" (str (:port (:nrepl dev)))]
                 (get-in (json/read-value (io/file dir ".mcp.json")) ["mcpServers" "clojure-mcp" "args"]))))
        (println "a line for the log")
        (finally (repl/stop! dev)))
      (is (not (.exists (io/file dir ".nrepl-port"))) "stopped: the port file is gone")
      (let [log (slurp (io/file dir ".groundwork" "dev.log"))]
        (is (str/includes? log "Groundwork is running"))
        (is (str/includes? log "a line for the log")))
      (testing "the next run keeps the last log"
        (let [dev (repl/start! (-> settings (assoc :environment :development) (assoc-in [:server :port] 0))
                               {:dir dir})]
          (repl/stop! dev)
          (is (str/includes? (slurp (io/file dir ".groundwork" "dev.log.1")) "a line for the log")))))))

(deftest the-mcp-json-keeps-other-servers
  (let [dir (str (Files/createTempDirectory "groundwork-mcp" (make-array FileAttribute 0)))
        f   (io/file dir ".mcp.json")]
    (spit f "{\"mcpServers\": {\"other\": {\"type\": \"stdio\", \"command\": \"x\"}}}")
    (repl/write-mcp-json! dir 7888)
    (let [servers (get (json/read-value f) "mcpServers")]
      (is (= #{"other" "clojure-mcp"} (set (keys servers))))
      (is (= "7888" (last (get-in servers ["clojure-mcp" "args"])))))
    (let [modified (.lastModified f)]
      (Thread/sleep 20)
      (repl/write-mcp-json! dir 7888)
      (is (= modified (.lastModified f)) "unchanged: not written again"))))
