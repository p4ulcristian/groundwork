(ns groundwork.limits-test
  "Rate limits: buckets refilled over a minute, daily caps in Postgres, the
   :public default, and both kinds of call turned away with which limit and
   when to come back."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.endpoints :as endpoints]
   [groundwork.limits :as limits]
   [groundwork.test-support :refer [app-settings sql!]]
   [groundwork.web :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.util UUID)
   (java.util.concurrent ConcurrentHashMap)))

(deftest a-bucket-refills-over-the-minute
  (let [buckets (ConcurrentHashMap.)]
    (is (= [nil nil nil] (repeatedly 3 #(limits/take-token! buckets :k 3 0))) "a burst up to its size")
    (is (= 20 (limits/take-token! buckets :k 3 0)) "then the seconds until one more")
    (is (nil? (limits/take-token! buckets :k 3 20000)) "a third of a minute later, one is back")
    (is (some? (limits/take-token! buckets :k 3 20000)))))

(deftest callers
  (let [person (UUID/randomUUID)]
    (is (= [:person (str person)] (limits/caller nil {:person person :client-ip "1.2.3.4"})))
    (is (= [:client-ip "1.2.3.4"] (limits/caller nil {:person nil :client-ip "1.2.3.4"})))
    (is (= [:client-ip "1.2.3.4"] (limits/caller :person {:person nil :client-ip "1.2.3.4"})) "no one to count")
    (is (= [:scope "a,b"] (limits/caller :scope {:scopes #{"b" "a"}})))))

(def hits (atom []))

(def endpoint-map
  {:site/hello  {:read       (fn [_ _] "hello")
                 :permission :public
                 :input      [:map]
                 :answer     :string}
   :site/ai     {:write      (fn [_ _] "suggestion")
                 :permission :signed-in
                 :input      [:map]
                 :answer     :string
                 :rate-limit {:per-day 2 :by :person}}
   :site/free   {:read       (fn [_ _] "free")
                 :permission :signed-in
                 :input      [:map]
                 :answer     :string}})

(def ^:dynamic *app* nil)

(use-fixtures :once
  (fn [t]
    (reset! hits [])
    (let [app (gw/start (-> app-settings
                            (assoc :limits {:public {:reads-per-minute 3 :writes-per-minute 1}
                                            :on-limit #(swap! hits conj %)})
                            (assoc-in [:parquery :endpoints] [endpoint-map])
                            (assoc-in [:web :routes]
                                      [["/open" {:get (fn [_] {:status 200 :body "open"}) :access :public}]
                                       ["/careful" {:get        (fn [_] {:status 200 :body "careful"})
                                                    :access     :public
                                                    :rate-limit {:per-minute 1 :by :client-ip}}]])))]
      (try
        (sql! "TRUNCATE groundwork.limit_counts")
        (binding [*app* app] (t))
        (finally (gw/stop app))))))

(defn- call [person endpoint]
  (endpoints/call (gw/endpoints *app*) {:endpoint endpoint :params {} :person person :flags #{}
                                        :request-id "r" :client-ip "10.1.1.1"}))

(defn- get-path [path]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))
                            (.method "GET" (HttpRequest$BodyPublishers/noBody)) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    {:status      (.statusCode response)
     :retry-after (first (.allValues (.headers response) "retry-after"))}))

(deftest a-public-endpoint-gets-the-default
  (is (= ["hello" "hello" "hello"] (map :data (repeatedly 3 #(call nil :site/hello)))))
  (let [answer (call nil :site/hello)]
    (is (= :too-many (:status answer)))
    (is (= :per-minute (:limit answer)))
    (is (pos-int? (:retry-after-seconds answer))))
  (testing "another caller has their own bucket"
    (is (= :ok (:status (endpoints/call (gw/endpoints *app*) {:endpoint :site/hello :params {} :person nil
                                                              :flags #{} :client-ip "10.9.9.9"})))))
  (is (some #(= :site/hello (:endpoint %)) @hits) "the hit went to :on-limit"))

(deftest a-daily-cap-counts-in-postgres
  (let [peter (UUID/randomUUID)
        pia   (UUID/randomUUID)]
    (is (= [:ok :ok] (map :status (repeatedly 2 #(call peter :site/ai)))))
    (let [answer (call peter :site/ai)]
      (is (= :too-many (:status answer)))
      (is (= :per-day (:limit answer)))
      (is (<= 1 (:retry-after-seconds answer) 86400) "until midnight UTC"))
    (is (= :ok (:status (call pia :site/ai))) "per person")
    (is (= 1 (-> (sql! "SELECT limit_count__n FROM groundwork.limit_counts WHERE limit_count__caller = ?"
                       (str "person:" pia))
                 first vals first))
        "counted in Postgres, by person")
    (is (= :ok (:status (call peter :site/free))) "no limit declared, signed in: none")))

(deftest a-route-is-limited-too
  (testing "a public route gets the default"
    (is (= [200 200 200] (map :status (repeatedly 3 #(get-path "/open")))))
    (is (= 429 (:status (get-path "/open")))))
  (testing "its own limit, with Retry-After"
    (is (= 200 (:status (get-path "/careful"))))
    (let [{:keys [status retry-after]} (get-path "/careful")]
      (is (= 429 status))
      (is (pos-int? (parse-long retry-after))))))

(deftest a-route-limit-counts-people-or-addresses
  (is (thrown-with-msg? Exception #"counts :person or :client-ip"
                        (web/handler {:routes [["/x" {:get identity :access :public :rate-limit {:per-day 1 :by :scope}}]]
                                      :max-body-bytes 1000 :content-security-policy {} :login-path "/login"
                                      :proxies {:trusted [] :client-ip-header nil :request-id-header nil}
                                      :limits (gw/part *app* :groundwork/limits)}))))
