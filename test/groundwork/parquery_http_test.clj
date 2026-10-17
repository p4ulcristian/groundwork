(ns groundwork.parquery-http-test
  "parquery over HTTP: a list of asks in, their answers out, in order; each
   runs apart, under the session and the Origin check."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [app-settings sql!]])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.util UUID)))

(def shop (UUID/randomUUID))
(def other-shop (UUID/randomUUID))
(def owner (UUID/randomUUID))
(def stranger (UUID/randomUUID))

(def description
  {:scopes  {:shop {:actions {:notes/see #{:read} :notes/add #{:read :insert}} :roles {} :parent nil}}
   :records {:note {:table :notes :id :note/id :whose {:note/shop-id :shop}}}
   :rules   [{:allow #{:notes/see :notes/add} :on :note :to [:member :note/shop-id]}]})

(pg/defsql pq-notes-of-shop)
(pg/defsql pq-add-note)
(pg/defsql pq-sleep)

(def endpoints
  {:notes/of-shop {:read       (fn [{:keys [db]} params] (pg/rows db pq-notes-of-shop params))
                   :permission {:action :notes/see :scope [:shop :shop/id]}
                   :input      [:map [:shop/id :uuid]]
                   :answer     [:vector [:map [:note/id :uuid] [:note/text :string]]]}
   :notes/add     {:write      (fn [{:keys [db]} params] (pg/row db pq-add-note params))
                   :permission {:action :notes/add :scope [:shop :shop/id]}
                   :input      [:map [:shop/id :uuid] [:note/id {:groundwork/record false} :uuid] [:note/text :string]]
                   :answer     [:map [:note/id :uuid] [:note/text :string]]}
   :site/sleep    {:read       (fn [{:keys [db]} params] (:slept/done (pg/row db pq-sleep params)))
                   :permission :public
                   :input      [:map [:seconds :double]]
                   :answer     :boolean}})

(def ^:private answered (atom []))

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS notes;
           CREATE TABLE notes (note__id uuid PRIMARY KEY, note__shop_id uuid NOT NULL, note__text text NOT NULL)")
    (let [app (gw/start (-> app-settings
                            (assoc :permissions description)
                            (assoc-in [:parquery :endpoints] [endpoints])
                            (assoc-in [:parquery :max-asks] 3)
                            (assoc-in [:parquery :on-answer] #(swap! answered conj %))))]
      (try
        (perm/create-scope! (gw/permissions app) {:kind :shop :id shop :parent nil :owner owner})
        (perm/create-scope! (gw/permissions app) {:kind :shop :id other-shop :parent nil :owner stranger})
        (t)
        (finally
          (gw/stop app)
          (sql! "DROP TABLE IF EXISTS notes"))))))

(def ^:private client (HttpClient/newHttpClient))
(def ^:private origin (get-in app-settings [:sessions :origin]))

(defn- post [path body & {:keys [cookie origin? headers] :or {origin? true}}]
  (let [b (cond-> (-> (HttpRequest/newBuilder (URI. (str origin path)))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString body)))
            origin? (.header "Origin" origin)
            cookie  (.header "Cookie" cookie))
        _ (doseq [[k v] headers] (.header b k v))
        r (.send client (.build b) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r)
     :body   (.body r)
     :cookie (some-> (.firstValue (.headers r) "set-cookie") (.orElse nil) (#(first (str/split % #";"))))}))

(defn- sign-in [person]
  (:cookie (post (str "/groundwork/test-sign-in?person=" person) ""
                 :origin? false :headers {"X-Test-Secret" "a-secret-only-tests-know"})))

(defn- ask [cookie & asks]
  (let [{:keys [status body]} (post "/parquery" (wire/write-json (vec asks)) :cookie cookie)]
    {:status status :answers (wire/read-json body)}))

(deftest asks-and-answers-in-order
  (let [cookie (sign-in owner)
        id     (UUID/randomUUID)]
    (is (= {:status 200 :answers [{:status "ok" :data {:note/id (str id) :note/text "first"}}]}
           (ask cookie {:endpoint "notes/add" :params {:shop/id (str shop) :note/id (str id) :note/text "first"}
                        :key (str (UUID/randomUUID))})))
    (is (= {:status 200
            :answers [{:status "ok" :data [{:note/id (str id) :note/text "first"}]}
                      {:status "not-found"}]}
           (ask cookie
                {:endpoint "notes/of-shop" :params {:shop/id (str shop)}}
                {:endpoint "notes/of-shop" :params {:shop/id (str other-shop)}}))
        "each answer where its ask was")))

(deftest one-failing-leaves-the-others
  (let [cookie (sign-in owner)
        {:keys [answers]} (ask cookie
                               {:endpoint "notes/of-shop" :params {:shop/id "nope"}}
                               {:endpoint "site/sleep" :params {:seconds 0}})]
    (is (= "invalid" (:status (first answers))))
    (is (= {:status "ok" :data true} (second answers)))))

(deftest asks-run-apart
  (let [started (System/nanoTime)
        {:keys [answers]} (ask nil
                               {:endpoint "site/sleep" :params {:seconds 0.4}}
                               {:endpoint "site/sleep" :params {:seconds 0.4}}
                               {:endpoint "site/sleep" :params {:seconds 0.4}})
        ms      (/ (- (System/nanoTime) started) 1e6)]
    (is (every? #(= "ok" (:status %)) answers))
    (is (< ms 1000) "three waits of 0.4 s took less than their sum")))

(deftest the-request-is-checked
  (testing "signed out"
    (is (= [{:status "signed-out"}] (:answers (ask nil {:endpoint "notes/of-shop" :params {:shop/id (str shop)}})))))
  (testing "too many asks in one request"
    (is (= 413 (:status (ask nil {:endpoint "site/sleep" :params {:seconds 0}} {:endpoint "site/sleep" :params {:seconds 0}}
                             {:endpoint "site/sleep" :params {:seconds 0}} {:endpoint "site/sleep" :params {:seconds 0}})))))
  (testing "not a list of asks"
    (is (= 400 (:status (post "/parquery" "{}"))))
    (is (= [{:status "invalid" :errors {:ask ["isn't an ask: {\"endpoint\": …, \"params\": {…}}"]}}]
           (:answers (ask nil 42)))))
  (testing "another site's page can't ask"
    (is (= 403 (:status (post "/parquery" "[]" :origin? false))))))

(deftest every-answer-is-heard
  (reset! answered [])
  (let [cookie (sign-in owner)]
    (post "/parquery" (wire/write-json [{:endpoint "notes/of-shop" :params {:shop/id (str shop)}}
                                        {:endpoint "notes/of-shop" :params {:shop/id (str other-shop)}}])
          :cookie cookie)
    (is (= #{[:notes/of-shop :ok] [:notes/of-shop :not-found]}
           (set (map (juxt :endpoint :status) @answered))))
    (is (every? #(and (number? (:ms %)) (= owner (:person %)) (string? (:request-id %))) @answered))))
