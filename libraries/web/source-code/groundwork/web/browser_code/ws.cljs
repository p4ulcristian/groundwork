(ns groundwork.web.browser-code.ws
  "ws in the browser: a WebSocket that reopens a second after a break,
   until it is closed on purpose."
  (:require
   [groundwork.wire.api :as wire]))

(defn- make-address [path]
  (str (if (= "https:" (.. js/location -protocol)) "wss://" "ws://") (.. js/location -host) path))

(defn open-ws-line!
  "Opens a websocket at path; (on-message data) for each message, its JSON
   read into data. Returns the line: {:socket (atom WebSocket) :closed? (atom false)}."
  [path on-message]
  (let [line {:socket (atom nil) :closed? (atom false)}]
    (letfn [(connect []
              (let [socket (js/WebSocket. (make-address path))]
                (set! (.-onmessage socket) (fn [event] (on-message (wire/turn-json-text-into-data (.-data event)))))
                (set! (.-onclose socket) (fn [_] (when-not @(:closed? line) (js/setTimeout connect 1000))))
                (reset! (:socket line) socket)))]
      (connect)
      line)))

(defn send-message-on-ws-line!
  "Sends data as JSON on the line. Whether it was open."
  [line data]
  (let [socket @(:socket line)]
    (if (= 1 (.-readyState socket))
      (do (.send socket (wire/turn-data-into-json-text data)) true)
      false)))

(defn close-ws-line!
  "Closes the line; it doesn't reopen."
  [line]
  (reset! (:closed? line) true)
  (.close @(:socket line)))
