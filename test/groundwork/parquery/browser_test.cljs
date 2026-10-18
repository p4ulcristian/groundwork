(ns groundwork.parquery.browser-test
  "parquery's browser half, with the network replaced: asks of one moment
   share a request, answers come back typed, the line resends what's on
   screen after a drop."
  (:require
   [cljs.test :refer [async deftest is]]
   [groundwork.parquery.http :as http]
   [groundwork.parquery.websocket :as websocket]
   [groundwork.parquery.wire :as wire]))

(def shop-id #uuid "7f3c1e2a-0000-4000-8000-000000000001")

(deftest asks-of-one-moment-share-a-request
  (async done
         (let [requests (atom [])
               answers  (atom [])]
           (http/set-shapes! {:jobs/list {:input [:map [:shop/id :uuid]]
                                          :answer [:vector [:map [:job/id :uuid] [:job/due :time/local-date]]]}})
           (reset! http/transport
                   (fn [body on-text _]
                     (swap! requests conj (wire/read-json body))
                     (on-text (wire/write-json [{:status "ok" :data [{:job/id (str shop-id) :job/due "2026-11-15"}]}
                                                {:status "not-found"}]))))
           (http/ask! {:endpoint :jobs/list :params {:shop/id shop-id}} #(swap! answers conj %))
           (http/ask! {:endpoint :jobs/list :params {:shop/id shop-id}} #(swap! answers conj %))
           (js/setTimeout
            (fn []
              (is (= 1 (count @requests)) "one request")
              (is (= [{:endpoint "jobs/list" :params {:shop/id (str shop-id)}}
                      {:endpoint "jobs/list" :params {:shop/id (str shop-id)}}]
                     (first @requests))
                  "params went out as text")
              (let [[ok missing] @answers]
                (is (= :ok (:status ok)))
                (is (= (str shop-id) (get-in ok [:data 0 :job/id])) "the id came back as text")
                (is (= "2026-11-15" (str (get-in ok [:data 0 :job/due]))) "the day came back a day")
                (is (= {:status :not-found} missing)))
              (done))
            20))))

(deftest a-request-that-never-arrives
  (async done
         (let [answers (atom [])]
           (reset! http/transport (fn [_ _ on-failure] (on-failure "No connection to the server")))
           (http/ask! {:endpoint :jobs/list :params {:shop/id shop-id}} #(swap! answers conj %))
           (js/setTimeout
            (fn []
              (is (= [{:status :error :message "No connection to the server" :reached? false}] @answers))
              ;; the server did answer, with an error of its own
              (reset! http/transport (fn [_ _ on-failure] (on-failure "The server answered 502" true)))
              (http/ask! {:endpoint :jobs/list :params {:shop/id shop-id}} #(swap! answers conj %))
              (js/setTimeout
               (fn []
                 (is (= {:status :error :message "The server answered 502"} (last @answers)))
                 (done))
               20))
            20))))

(defn- fake-socket [sockets]
  (fn [url]
    (let [s #js {:readyState 0 :url url :sent #js []}]
      (set! (.-send s) (fn [text] (.push (.-sent s) text)))
      (set! (.-close s) (fn [] (set! (.-readyState s) 3) ((.-onclose s) #js {})))
      (swap! sockets conj s)
      s)))

(defn- open! [s]
  (set! (.-readyState s) 1)
  ((.-onopen s) #js {}))

(deftest the-line-tells-and-hears
  (async done
         (let [sockets     (atom [])
               changed     (atom [])
               reconnected (atom 0)
               statuses    (atom [])
               jobs        ["jobs/list" {:shop/id (str shop-id)}]]
           (reset! websocket/make-socket (fake-socket sockets))
           (websocket/start! {:url "ws://test/parquery" :min-wait-ms 5
                              :on-changed #(swap! changed conj %) :on-reconnected #(swap! reconnected inc)
                              :on-status #(swap! statuses conj %)})
           (websocket/watch! [jobs])
           (let [^js first-socket (first @sockets)]
             (open! first-socket)
             (is (= [{:watch [jobs]}] (map wire/read-json (.-sent first-socket))) "what's on screen, once open")
             ((.-onmessage first-socket) #js {:data (wire/write-json {:changed [jobs]})})
             (is (= [[jobs]] @changed))
             ((.-close first-socket))
             (js/setTimeout
              (fn []
                (let [^js second-socket (second @sockets)]
                  (is (some? second-socket) "it came back by itself")
                  (open! second-socket)
                  (is (= [{:watch [jobs]}] (map wire/read-json (.-sent second-socket))) "and said again what's on screen")
                  (is (= 1 @reconnected) "bells may have been missed")
                  (is (= [:open :closed :open] @statuses) "the line's status, as it comes and goes")
                  (websocket/stop!)
                  (done)))
              30)))))
