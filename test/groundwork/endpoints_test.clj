(ns groundwork.endpoints-test
  "Endpoints checked and run: shapes, permissions on every id, the wall,
   read-only reads, writes that run once, errors that say little."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.endpoints :as endpoints]
   [groundwork.migrations :as migrations]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [db-settings sql!]]
   [next.jdbc])
  (:import
   (java.util UUID)))

(defn- id [] (UUID/randomUUID))

(def acme (id)) (def zinc (id))
(def paul (id)) (def peter (id)) (def ana (id)) (def kati (id))
(def acme-order (id)) (def zinc-order (id)) (def kati-order (id))
(def acme-job (id)) (def zinc-job (id))
(def acme-machine (id)) (def zinc-machine (id))

(def description
  {:scopes  {:shop {:actions #{:orders/see :jobs/move}
                    :roles   {"Production" #{:orders/see :jobs/move}}
                    :parent  nil}}
   :records {:order   {:table :orders :id :order/id :in [:shop :order/shop-id]
                       :people {:orders/see :order/customer-id}}
             :job     {:table :jobs :id :job/id :in [:shop :job/shop-id] :parent [:order :job/order-id]}
             :machine {:table :machines :id :machine/id :in [:shop :machine/shop-id]}}
   :links   []
   :passes []
   :open {:anyone #{} :signed-in #{}}
   :session-blocks {:impersonated #{:jobs/move}}})

(def tables
  "DROP TABLE IF EXISTS jobs, orders, machines;
   CREATE TABLE orders (order__id uuid PRIMARY KEY, order__shop_id uuid NOT NULL, order__customer_id uuid,
                        UNIQUE (order__id, order__shop_id));
   CREATE TABLE jobs (job__id uuid PRIMARY KEY, job__order_id uuid NOT NULL, job__shop_id uuid NOT NULL,
                      job__note text,
                      FOREIGN KEY (job__order_id, job__shop_id)
                        REFERENCES orders (order__id, order__shop_id) ON UPDATE CASCADE);
   CREATE TABLE machines (machine__id uuid PRIMARY KEY, machine__shop_id uuid NOT NULL);")

(pg/defsql ep-jobs-of-shop)
(pg/defsql ep-note-job)
(pg/defsql ep-all-orders)
(pg/defsql ep-insert-machine)

(def runs (atom 0))
(def later (atom nil))
(def parts (atom nil))
(pg/defsql ep-job-note)
(def errors (atom []))

(def jobs-endpoints
  {:jobs/of-shop {:read       (fn [{:keys [db]} params] (pg/rows db ep-jobs-of-shop params))
                  :permission {:action :orders/see :scope [:shop :shop/id]}
                  :input      [:map [:shop/id :uuid]]
                  :answer     [:vector [:map [:job/id :uuid]]]}
   :jobs/note    {:write      (fn [{:keys [db]} params]
                                (swap! runs inc)
                                (pg/row db ep-note-job params))
                  :permission {:action :jobs/move :record [:job :job/id]}
                  :input      [:map [:job/id :uuid] [:job/note :string]
                               [:machine/id {:groundwork/record :machine} :uuid]]
                  :answer     [:map [:job/id :uuid] [:job/note :string]]}
   ;; an app whose shapes take ids as text
   :jobs/as-text {:read       (fn [_ params] {:job/id (:job/id params)})
                  :permission {:action :jobs/move :record [:job :job/id]}
                  :input      [:map [:job/id :string]
                               [:machine/id {:groundwork/record :machine} :string]]
                  :answer     [:map [:job/id :string]]}
   :jobs/later   {:write      (fn [{:keys [db after-commit]} params]
                                (pg/row db ep-note-job params)
                                (after-commit (fn [_db]
                                                (reset! later (perm/as-system (:perms @parts)
                                                                              (fn [tx] (:job/note (pg/row tx ep-job-note params)))))))
                                (when (= "refuse" (:job/note params))
                                  (throw (endpoints/refuse "Not this one")))
                                nil)
                  :permission {:action :jobs/move :record [:job :job/id]}
                  :input      [:map [:job/id :uuid] [:job/note :string]]
                  :answer     :nil}
   :jobs/refused {:write      (fn [_ _] (throw (endpoints/refuse "This job is finished")))
                  :permission {:action :jobs/move :record [:job :job/id]}
                  :input      [:map [:job/id :uuid]]
                  :answer     :nil}})

(def other-endpoints
  {:orders/mine   {:read       (fn [{:keys [db]} _] (mapv :order/id (pg/rows db ep-all-orders)))
                   :permission {:action :orders/see :list :order}
                   :input      [:map]
                   :answer     [:vector :uuid]}
   :acme/notice   {:read       (fn [{:keys [request]} _] (str "for acme " (get-in request [:headers "x-note"])))
                   :permission {:action :orders/see :scope [:shop acme]}
                   :input      [:map]
                   :answer     :string}
   :site/hello    {:read       (fn [{:keys [person]} _] (if person "hello, you" "hello"))
                   :permission :public
                   :input      [:map]
                   :answer     :string}
   :site/broken   {:read       (fn [_ _] 42)
                   :permission :signed-in
                   :input      [:map]
                   :answer     :string}
   :site/sneaky   {:read       (fn [{:keys [db]} _]
                                 (pg/execute! db ep-insert-machine {:machine/id (id) :machine/shop-id acme})
                                 "wrote")
                   :permission {:action :orders/see :scope [:shop :shop/id]}
                   :input      [:map [:shop/id :uuid]]
                   :answer     :string}})

(def settings
  {:endpoints   [jobs-endpoints other-endpoints]
   :max-asks    50
   :max-running 4
   :bearer      (fn [token] (when (= "peters-token" token) peter))
   :on-answer   nil
   :bells       {:global {} :personal {}}})

(def ^:dynamic *part* nil)

(defn- join! [perms by scope person role]
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
                        groundwork.extra_actions, groundwork.invitations, groundwork.run_once CASCADE")
        (sql! (str "INSERT INTO orders VALUES (?, ?, NULL), (?, ?, NULL), (?, ?, ?);"
                   "INSERT INTO jobs VALUES (?, ?, ?, NULL), (?, ?, ?, NULL);"
                   "INSERT INTO machines VALUES (?, ?), (?, ?);")
              acme-order acme zinc-order zinc kati-order zinc kati
              acme-job acme-order acme zinc-job zinc-order zinc
              acme-machine acme zinc-machine zinc)
        (let [perms (perm/start {:settings description :db db})
              _     (reset! parts {:perms perms})]
          (perm/create-scope! perms {:kind :shop :id acme :parent nil :owner paul})
          (perm/create-scope! perms {:kind :shop :id zinc :parent nil :owner ana})
          (join! perms paul acme peter "Production")
          (binding [*part* (endpoints/start {:settings     settings :db db :permissions perms
                                             :app-settings {:web {:on-error #(swap! errors conj %)}}})]
            (t)))
        (finally
          (pg/stop db)
          (sql! "DROP TABLE IF EXISTS jobs, orders, machines"))))))

(defn- call [person endpoint params & {:as more}]
  (endpoints/call *part* (merge {:endpoint endpoint :params params :person person :flags #{} :request-id "r1"} more)))

(defn- start-error [endpoints-maps]
  (try (endpoints/start {:settings (assoc settings :endpoints endpoints-maps) :db (:db *part*)
                         :permissions (:perms *part*) :app-settings {}})
       nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest the-list-is-checked-at-start
  (testing "an id field must say which record it holds"
    (is (re-find #"holds ids"
                 (start-error [{:jobs/x {:read (fn [_ _]) :permission {:action :jobs/move :record [:job :job/id]}
                                         :input [:map [:job/id :uuid] [:machine/id :uuid]] :answer :any}}]))))
  (testing "a name in two areas"
    (is (re-find #"named twice" (start-error [jobs-endpoints jobs-endpoints]))))
  (testing "a permission naming an unknown action"
    (is (re-find #"no action" (start-error [{:jobs/x {:read (fn [_ _]) :permission {:action :nope/x :list :order}
                                                      :input [:map] :answer :any}}]))))
  (testing "every declaration is required"
    (is (re-find #":jobs/x :answer is missing" (start-error [{:jobs/x {:read (fn [_ _]) :permission :public :input [:map]}}])))))

(deftest a-scope-permission
  (is (= {:status :ok :data [{:job/id acme-job}]}
         (call peter :jobs/of-shop {:shop/id (str acme)}))
      "params arrive as JSON gave them; the input shape makes the id a UUID")
  (is (= {:status :not-found} (call peter :jobs/of-shop {:shop/id (str zinc)})) "another shop")
  (is (= {:status :signed-out} (call nil :jobs/of-shop {:shop/id (str acme)})))
  (is (= :invalid (:status (call peter :jobs/of-shop {:shop/id "not an id"})))))

(deftest a-fixed-scope-permission
  (testing "one scope named in the permission itself, for an area every member shares"
    (is (= {:status :ok :data "for acme hi"}
           (call peter :acme/notice {} :request {:headers {"x-note" "hi"}}))
        "the Ring request the ask came in is on ctx")
    (is (= {:status :not-found} (call ana :acme/notice {})) "a member of another shop")))

(deftest work-after-the-commit
  (is (= {:status :ok :data nil} (call peter :jobs/later {:job/id acme-job :job/note "cured"})))
  (is (= "cured" @later) "it runs after the commit, and sees what was written")
  (reset! later nil)
  (is (= :refused (:status (call peter :jobs/later {:job/id acme-job :job/note "refuse"}))))
  (is (nil? @later) "nothing runs after a rollback"))

(deftest every-id-in-a-request-is-checked
  (is (= {:status :ok :data {:job/id acme-job :job/note "sanded"}}
         (call peter :jobs/note {:job/id acme-job :job/note "sanded" :machine/id acme-machine})))
  (is (= {:status :not-found} (call peter :jobs/note {:job/id acme-job :job/note "x" :machine/id zinc-machine}))
      "a machine of another shop")
  (is (= {:status :not-found} (call peter :jobs/note {:job/id zinc-job :job/note "x" :machine/id acme-machine}))
      "a job of another shop")
  (is (= {:status :not-found} (call peter :jobs/note {:job/id acme-job :job/note "x" :machine/id acme-machine}
                                    :flags #{:impersonated}))
      "a session flag blocks the action"))

(deftest an-id-sent-as-text-is-checked-like-an-id
  (is (= {:status :ok :data {:job/id (str acme-job)}}
         (call peter :jobs/as-text {:job/id (str acme-job) :machine/id (str acme-machine)})))
  (is (= {:status :not-found} (call peter :jobs/as-text {:job/id (str zinc-job) :machine/id (str acme-machine)})))
  (is (= {:status :not-found} (call peter :jobs/as-text {:job/id (str acme-job) :machine/id (str zinc-machine)}))))

(deftest a-write-runs-once-per-key
  (reset! runs 0)
  (let [key  (str (id))
        ask  {:job/id acme-job :job/note "once" :machine/id acme-machine}
        one  (call peter :jobs/note ask :key key)
        two  (call peter :jobs/note ask :key key)]
    (is (= one two {:status :ok :data {:job/id acme-job :job/note "once"}}))
    (is (= 1 @runs) "the repeat gets the first answer back")
    (is (= :refused (:status (call paul :jobs/note ask :key key))) "someone else's key")))

(deftest a-list-sees-only-its-scopes
  (is (= {:status :ok :data [acme-order]} (call peter :orders/mine {})))
  (is (= {:status :ok :data [kati-order]} (call kati :orders/mine {})) "her own order, nothing else of Zinc's"))

(deftest errors-say-little
  (is (= {:status :refused :message "This job is finished"} (call peter :jobs/refused {:job/id acme-job})))
  (reset! errors [])
  (let [a (call peter :site/broken {})]
    (is (= {:status :error :message "Something went wrong. Please try again." :request-id "r1"} a))
    (is (= :site/broken (:endpoint (first @errors))) "the details go to the hook"))
  (testing "a read can't write"
    (reset! errors [])
    (is (= :error (:status (call peter :site/sneaky {:shop/id acme}))))
    (is (= 2 (perm/as-system (:perms *part*)
                             (fn [tx] (count (next.jdbc/execute! (::pg/conn tx) ["SELECT * FROM machines"])))))))
  (is (= {:status :not-found} (call peter :nope/nothing {}))))

(deftest public-and-tokens
  (is (= {:status :ok :data "hello"} (call nil :site/hello {})))
  (is (= {:status :ok :data [acme-order]}
         (endpoints/call-with-token *part* "peters-token" {:endpoint :orders/mine :params {}})))
  (is (= {:status :signed-out}
         (endpoints/call-with-token *part* "wrong" {:endpoint :orders/mine :params {}}))))

(deftest the-browser-asks-what-it-may-do
  (is (= #{[acme :orders/see] [acme :jobs/move]}
         (set (map (juxt :grant/scope-id :grant/action) (:data (call peter :groundwork/grants {})))))))
