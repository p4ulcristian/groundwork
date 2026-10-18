(ns groundwork.endpoints.store-events-test
  "The store's re-frame wiring, with only the network faked: ensure asks once
   per question, release and five minutes later the entry goes, bells are
   gathered and only what's on screen is asked again, the newest answer
   counts, writes are sent once with a key and never kept."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [groundwork.endpoints.http :as http]
   [groundwork.endpoints.websocket :as websocket]
   [groundwork.endpoints.wire :as wire]
   [groundwork.endpoints.store :as store]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]))

(def shop "acme")
(def jobs {:endpoint :jobs/list :params {:shop/id shop}})
(def machines {:endpoint :machines/list :params {:shop/id shop}})
(def jobs-key [:jobs/list {:shop/id shop}])
(def machines-key [:machines/list {:shop/id shop}])
(def jobs-form ["jobs/list" {:shop/id shop}])
(def machines-form ["machines/list" {:shop/id shop}])

(def requests (atom []))
(def sockets (atom []))
(def now (atom 1000))

(defn- answer-for [{:keys [endpoint key]}]
  (case endpoint
    "jobs/list"     {:status "ok" :data [{:job/id "j1"}]}
    "machines/list" {:status "ok" :data [{:machine/id "m1"}]}
    "jobs/move"     (if key {:status "ok" :data {:job/id "j1"}} {:status "invalid"})
    "jobs/rename"   {:status "ok" :data {:job/id "j1"} :changed [["jobs/list" {:shop/id "acme"}]] :tag "w1"}
    {:status "not-found"}))

(defn- fake-transport [body on-text _]
  (let [asks (wire/read-json body)]
    (swap! requests conj asks)
    (js/setTimeout #(on-text (wire/write-json (mapv answer-for asks))) 1)))

(defn- fake-socket [url]
  (let [s #js {:readyState 0 :url url :sent #js []}]
    (set! (.-send s) (fn [text] (.push (.-sent s) text)))
    (set! (.-close s) (fn [] (set! (.-readyState s) 3)))
    (swap! sockets conj s)
    s))

(defn- fresh! []
  (reset! requests [])
  (reset! sockets [])
  (reset! now 1000)
  (reset! rf-db/app-db {})
  (reset! http/transport fake-transport)
  (reset! websocket/make-socket fake-socket)
  (rf/reg-cofx ::store/now (fn [cofx] (assoc cofx :now @now)))
  (rf/dispatch-sync [::store/start {:shapes {:jobs/list     {:input [:map [:shop/id :string]]
                                                             :answer [:vector [:map [:job/id :string]]]}
                                             :machines/list {:input [:map [:shop/id :string]]
                                                             :answer [:vector [:map [:machine/id :string]]]}}
                                    :line   {:url "ws://test/parquery" :min-wait-ms 5}}])
  (let [^js s (first @sockets)]
    (set! (.-readyState s) 1)
    ((.-onopen s) #js {})))

(defn- entry [k] (get-in @rf-db/app-db [:groundwork/parquery-store :entries k]))
(defn- answer [ask] @(rf/subscribe [::store/answer ask]))
(defn- asked [] (mapv #(mapv :endpoint %) @requests))
(defn- watched [] (some-> ^js (last @sockets) .-sent last wire/read-json :watch))

(defn- later [ms f] (js/setTimeout f ms))

(deftest ensure-asks-once-per-question
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/ensure :board [jobs]])
         (rf/dispatch-sync [::store/ensure :panel [jobs]])
         (is (= :loading (:status (answer jobs))))
         (is (= [jobs-form] (watched)) "the line hears what's on screen")
         (later 30 (fn []
                     (is (= [["jobs/list"]] (asked)) "two owners, one request")
                     (is (= {:status :ready :data [{:job/id "j1"}]} (select-keys (answer jobs) [:status :data])))
                     (is (= #{:board :panel} (:owners (entry jobs-key))))
                     (done)))))

(deftest release-and-five-minutes-later-it-goes
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/ensure :board [jobs machines]])
         (later 30 (fn []
                     (rf/dispatch-sync [::store/release :board])
                     (is (= [] (watched)) "nothing on screen anymore")
                     (is (= 1000 (:released-at (entry jobs-key))) "kept a while, for going back")
                     (reset! now (+ 1000 store/gc-ms -1))
                     (rf/dispatch-sync [::store/ensure :other [machines]])
                     (is (some? (entry jobs-key)) "not yet")
                     (reset! now (+ 1000 store/gc-ms))
                     (rf/dispatch-sync [::store/release :other])
                     (is (nil? (entry jobs-key)) "five minutes unneeded: gone")
                     (is (some? (entry machines-key)) "needed until just now: kept")
                     (done)))))

(deftest bells-are-gathered-and-only-whats-on-screen-is-asked-again
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/ensure :board [jobs]])
         (rf/dispatch-sync [::store/ensure :panel [machines]])
         (later 30 (fn []
                     (rf/dispatch-sync [::store/release :panel])
                     (reset! requests [])
                     (let [^js s (first @sockets)]
                       ((.-onmessage s) #js {:data (wire/write-json {:changed [jobs-form machines-form]})})
                       ((.-onmessage s) #js {:data (wire/write-json {:changed [jobs-form]})}))
                     (later 100 (fn []
                                  (is (= [] @requests) "gathered for a moment first")
                                  (is (:stale? (entry jobs-key)))
                                  (later 200 (fn []
                                               (is (= [["jobs/list"]] (asked))
                                                   "one refresh, only for what's on screen")
                                               (is (= :ready (:status (answer jobs))) "old data showed meanwhile")
                                               (done)))))))))

(deftest a-bell-while-the-question-is-out-asks-again-after-the-answer
  (async done
         (fresh!)
         ;; a slow server: the answer takes 400 ms
         (reset! http/transport (fn [body on-text _]
                                  (let [asks (wire/read-json body)]
                                    (swap! requests conj asks)
                                    (js/setTimeout #(on-text (wire/write-json (mapv answer-for asks))) 400))))
         (rf/dispatch-sync [::store/ensure :board [jobs]])
         (later 20 (fn []
                     (let [^js s (first @sockets)]
                       ((.-onmessage s) #js {:data (wire/write-json {:changed [jobs-form]})}))
                     (later 300 (fn []
                                  (is (= [["jobs/list"]] (asked)) "not asked twice at once")
                                  (is (:stale? (entry jobs-key)) "but remembered as changed")
                                  (later 450 (fn []
                                               (is (= [["jobs/list"] ["jobs/list"]] (asked))
                                                   "the answer may be from before the change: asked again")
                                               (later 450 (fn []
                                                            (is (not (:stale? (entry jobs-key))))
                                                            (is (= 2 (count @requests)) "and then it rests")
                                                            (done)))))))))))

(deftest the-newest-answer-counts
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/ensure :board [jobs]])
         (later 30 (fn []
                     (let [n (:request-id (entry jobs-key))]
                       (rf/dispatch-sync [::store/-answered jobs-key (dec n) {:status :ok :data [{:job/id "stale"}]}])
                       (is (= [{:job/id "j1"}] (:data (answer jobs))) "a late answer to an older request is dropped")
                       (done))))))

(deftest a-line-that-came-back-asks-everything-on-screen-again
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/ensure :board [jobs]])
         (later 30 (fn []
                     (reset! requests [])
                     (rf/dispatch-sync [::store/-reconnected])
                     (later 30 (fn []
                                 (is (= [["jobs/list"]] (asked)))
                                 (done)))))))

(deftest a-write-is-sent-once-with-a-key-and-never-kept
  (async done
         (fresh!)
         (let [answers (atom [])]
           (rf/reg-event-db ::moved (fn [db [_ a]] (swap! answers conj a) db))
           (rf/dispatch-sync [::store/write {:endpoint :jobs/move :params {:job/id "j1"}} [::moved]])
           (later 30 (fn []
                       (testing "one request, carrying a one-time key"
                         (is (= [["jobs/move"]] (asked)))
                         (is (string? (:key (ffirst @requests)))))
                       (is (= [:ok] (map :status @answers)) "its answer went to the given event")
                       (is (nil? (entry [:jobs/move {:job/id "j1"}])) "and the store kept nothing")
                       (done))))))

(deftest asking-again-now-and-once-more-when-the-answer-lands
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/ensure :board [jobs]])
         ;; out: asked once more when the answer lands
         (rf/dispatch-sync [::store/ask-again jobs])
         (rf/dispatch-sync [::store/ask-again machines])
         (later 30 (fn []
                     (is (= [["jobs/list"] ["jobs/list"]] (asked)) "nothing for a question nobody ensured")
                     (later 30 (fn []
                                 ;; at rest: asked now
                                 (rf/dispatch-sync [::store/ask-again jobs])
                                 (later 30 (fn []
                                             (is (= 3 (count @requests)))
                                             (is (= :ready (:status (answer jobs))))
                                             (done)))))))))

(deftest a-write-says-what-is-on-screen-and-its-answer-what-changed
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/ensure :board [jobs machines]])
         (later 30 (fn []
                     (let [answers (atom [])]
                       (rf/reg-event-db ::renamed (fn [db [_ a]] (swap! answers conj a) db))
                       (rf/dispatch-sync [::store/write {:endpoint :jobs/rename :params {:job/id "j1"}} [::renamed]])
                       (later 30 (fn []
                                   (testing "the write named the reads on screen"
                                     (is (= #{jobs-form machines-form}
                                            (set (:watching (first (filter #(= "jobs/rename" (:endpoint %)) (apply concat @requests))))))))
                                   (testing "what it changed was asked again at once, and nothing else"
                                     (is (= [["jobs/list" "machines/list"] ["jobs/rename"] ["jobs/list"]] (asked))))
                                   (is (= [{:status :ok :data {:job/id "j1"}}] @answers) "the event got the plain answer")
                                   ;; the websocket's bell for the same write
                                   (let [^js s (first @sockets)]
                                     ((.-onmessage s) #js {:data (wire/write-json {:changed [jobs-form] :tag "w1"})}))
                                   (later 300 (fn []
                                                (is (= 3 (count @requests)) "not asked twice for one change")
                                                (done))))))))))

(deftest a-write-that-brings-its-key-keeps-it
  (async done
         (fresh!)
         (rf/dispatch-sync [::store/write {:endpoint :jobs/move :params {:job/id "j1"} :key "the-forms-key"}])
         (later 30 (fn []
                     (is (= "the-forms-key" (:key (ffirst @requests))))
                     (done)))))

(deftest waiting-for-an-answer-and-hearing-of-a-failed-read
  (async done
         (fresh!)
         (let [told (atom [])]
           (rf/reg-event-db ::loaded (fn [db [_ data]] (swap! told conj [:loaded data]) db))
           (rf/reg-event-db ::failed (fn [db [_ ask answer]] (swap! told conj [:failed (:endpoint ask) (:status answer)]) db))
           (rf/dispatch-sync [::store/start {:shapes         {}
                                             :line           {:url "ws://test/parquery" :min-wait-ms 5}
                                             :on-read-failed [::failed]}])
           (rf/dispatch-sync [::store/ensure :board [jobs {:endpoint :nothing/here :params {}}]])
           (rf/dispatch-sync [::store/once-answered jobs [::loaded]])
           (later 30 (fn []
                       (is (= #{[:loaded [{:job/id "j1"}]] [:failed :nothing/here :not-found]} (set @told)))
                       ;; the answer is there and nothing is out: told at once
                       (rf/dispatch-sync [::store/once-answered jobs [::loaded]])
                       (later 5 (fn []
                                  (is (= 2 (count (filter #(= :loaded (first %)) @told))))
                                  (done))))))))

(deftest fresh-for-a-while-asks-nothing
  (async done
         (fresh!)
         (let [ask (assoc jobs :fresh-ms 60000)]
           (rf/dispatch-sync [::store/ensure :board [ask]])
           (later 30 (fn []
                       (rf/dispatch-sync [::store/ensure :panel [ask]])
                       (later 30 (fn []
                                   (is (= [["jobs/list"]] (asked)) "still fresh: not asked again")
                                   (swap! now + 61000)
                                   (rf/dispatch-sync [::store/ensure :sheet [ask]])
                                   (later 30 (fn []
                                               (is (= 2 (count @requests)) "past its time: asked")
                                               (done))))))))))

(deftest hearing-the-bell-for-a-list-something-else-keeps
  (async done
         (fresh!)
         (let [told (atom 0)]
           (rf/reg-event-db ::reload (fn [db _] (swap! told inc) db))
           (rf/dispatch-sync [::store/hear :jobs-table [jobs] [::reload]])
           (is (= [jobs-form] (watched)) "its read is watched, with no entry kept")
           (is (nil? (entry jobs-key)))
           (rf/dispatch-sync [::store/-changed [machines-form] nil])
           (rf/dispatch-sync [::store/-changed [jobs-form] nil])
           (rf/dispatch-sync [::store/-reconnected])
           (later 10 (fn []
                       (is (= 2 @told) "told when its read changed, and when the line came back")
                       (rf/dispatch-sync [::store/release :jobs-table])
                       (rf/dispatch-sync [::store/-changed [jobs-form] nil])
                       (later 10 (fn []
                                   (is (= 2 @told) "released: it hears nothing")
                                   (is (= [] (watched)))
                                   (done))))))))

(deftest nothing-asks-before-the-start
  (reset! requests [])
  (reset! rf-db/app-db {})
  (reset! http/transport fake-transport)
  (let [jobs    {:endpoint :jobs/list :params {:shop/id "s1"}}
        refused (fn [event]
                  (try (rf/dispatch-sync event) nil
                       (catch :default e {:message (ex-message e) :data (ex-data e)})))]
    (testing "a question before the start is refused, naming the endpoint"
      (let [problem (refused [::store/ensure :board [jobs]])]
        (is (= ::store/asked-before-start (:type (:data problem))))
        (is (= [:jobs/list] (:endpoints (:data problem))))
        (is (re-find #":jobs/list" (:message problem)))
        (is (re-find #"before the app's start" (:message problem)))))
    (testing "every way of asking: own, ask-again, a write, waiting for an answer, hearing"
      (doseq [event [[::store/own :board [jobs]]
                     [::store/ask-again jobs]
                     [::store/write {:endpoint :jobs/move :params {:job/id "j1"}} [::moved]]
                     [::store/once-answered jobs [::loaded]]
                     [::store/hear :table [jobs] [::rang]]]]
        (is (= ::store/asked-before-start (:type (:data (refused event)))) (pr-str (first event)))))
    (testing "nothing went out, and nothing was kept"
      (is (= [] @requests))
      (is (nil? (:groundwork/parquery-store @rf-db/app-db))))
    (testing "after the start, the same question is asked"
      (fresh!)
      (rf/dispatch-sync [::store/ensure :board [jobs]])
      (is (= :loading (:status (answer jobs)))))))
