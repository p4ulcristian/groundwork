(ns groundwork.parquery.websocket
  "parquery over a websocket, the browser half: the bells. One line to
   /parquery for the whole visit. It tells the server which reads are on
   screen and hears which of them changed.

     (websocket/start! {:on-changed (fn [forms] …) :on-reconnected (fn [] …)
                        :on-status  (fn [status] …)})          ; :open or :closed
     (websocket/watch! [[\"jobs/list\" {:shop/id \"…\"}] …])   ; the whole list, each time

   A dropped line comes back by itself, waiting a little longer each time
   (up to 30 s); then it sends the list again and calls on-reconnected,
   since bells may have been missed."
  (:require
   [groundwork.parquery.wire :as wire]))

(defonce ^:private state
  (atom {:socket nil :watches [] :wait 0 :stopped? true :options nil :reconnecting? false}))

(defn- default-url []
  (let [location js/window.location]
    (str (if (= "https:" (.-protocol location)) "wss://" "ws://") (.-host location) "/parquery")))

(defonce ^{:doc "Makes a socket for a url; tests replace it."} make-socket
  (atom (fn [url] (js/WebSocket. url))))

(defn- send-watches! []
  (let [{:keys [socket watches]} @state]
    (when (and socket (= 1 (.-readyState socket)))
      (.send socket (wire/write-json {:watch watches})))))

(declare open!)

(defn- reconnect-later! []
  (let [{:keys [wait stopped?]} @state
        {:keys [min-wait-ms] :or {min-wait-ms 1000}} (:options @state)
        wait (min 30000 (max min-wait-ms (* 2 wait)))]
    (when-not stopped?
      (swap! state assoc :wait wait :reconnecting? true)
      (js/setTimeout open! wait))))

(defn- open! []
  (let [{:keys [url on-changed on-reconnected on-status]} (:options @state)
        socket (@make-socket (or url (default-url)))]
    (swap! state assoc :socket socket)
    (set! (.-onopen socket)
          (fn [_]
            (let [again? (:reconnecting? @state)]
              (swap! state assoc :wait 0 :reconnecting? false)
              (send-watches!)
              (when on-status (on-status :open))
              (when (and again? on-reconnected) (on-reconnected)))))
    (set! (.-onmessage socket)
          (fn [event]
            (when-let [changed (:changed (try (wire/read-json (.-data event)) (catch :default _ nil)))]
              (when on-changed (on-changed changed)))))
    (set! (.-onclose socket)
          (fn [_]
            (swap! state assoc :socket nil)
            (when on-status (on-status :closed))
            (reconnect-later!)))))

(defn start!
  "Open the line. on-changed gets the changed reads as [endpoint params]
   forms; on-reconnected is called when a dropped line is back; on-status,
   when given, hears :open and :closed as the line comes and goes."
  [options]
  (swap! state assoc :options options :stopped? false :wait 0 :reconnecting? false)
  (open!))

(defn watch!
  "The reads on screen now, as [endpoint params] forms."
  [forms]
  (swap! state assoc :watches (vec forms))
  (send-watches!))

(defn stop!
  "Close the line for good."
  []
  (swap! state assoc :stopped? true)
  (some-> (:socket @state) (.close)))
