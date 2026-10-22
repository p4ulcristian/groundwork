(ns groundwork.transport.browser-code.ws
  "ws in the browser: a WebSocket that reopens a second after a break,
   until it is closed on purpose."
  (:require
   [groundwork.wire.api :as wire]))

(defn- make-address [path]
  (str (if (= "https:" (.. js/location -protocol)) "wss://" "ws://") (.. js/location -host) path))

(defn- connect! [{:keys [socket closed?] :as line} path on-message]
  (let [websocket (js/WebSocket. (make-address path))]
    (set! (.-onmessage websocket) #(on-message (wire/turn-json-text-into-data (.-data %))))
    (set! (.-onclose websocket) #(when-not @closed? (js/setTimeout (fn [] (connect! line path on-message)) 1000)))
    (reset! socket websocket)))

(defn open-ws-line!
  "Opens a websocket at path; (on-message data) for each message, its JSON
   read into data. Returns the line."
  [path on-message]
  (doto {:socket (atom nil) :closed? (atom false)}
    (connect! path on-message)))

(defn send-message-on-ws-line!
  "Sends data as JSON on the line. Whether it was open."
  [{:keys [socket]} data]
  (let [open? (= 1 (.-readyState @socket))]
    (when open? (.send @socket (wire/turn-data-into-json-text data)))
    open?))

(defn close-ws-line!
  "Closes the line; it doesn't reopen."
  [{:keys [socket closed?]}]
  (reset! closed? true)
  (.close @socket))
