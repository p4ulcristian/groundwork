(ns groundwork.transport.browser-code.ws
  "ws in the browser: a WebSocket that reopens a second after a break,
   until it is closed on purpose. Each named message from the server goes
   to its handler, answers included."
  (:require
   [groundwork.transport.browser-code.messages :as messages]
   [groundwork.wire.api :as wire]))

(defn- make-full-address [address]
  (str (if (= "https:" (.. js/location -protocol)) "wss://" "ws://") (.. js/location -host) address))

(defn- connect! [{:keys [socket closed?] :as line} address handlers]
  (let [websocket (js/WebSocket. (make-full-address address))]
    (set! (.-onmessage websocket) #(messages/hand-named-messages! handlers (wire/turn-json-text-into-data (.-data %))))
    (set! (.-onclose websocket) #(when-not @closed? (js/setTimeout (fn [] (connect! line address handlers)) 1000)))
    (reset! socket websocket)))

(defn open-ws-line!
  "Opens the websocket; each named message goes to its handler. Returns the line."
  [address handlers]
  (doto {:socket (atom nil) :closed? (atom false)}
    (connect! address handlers)))

(defn send-message-on-ws-line!
  "Sends a named message on the line. Whether it was open."
  [{:keys [socket]} message]
  (let [open? (= 1 (.-readyState @socket))]
    (when open? (.send @socket (wire/turn-data-into-json-text message)))
    open?))

(defn close-ws-line!
  "Closes the line; it doesn't reopen."
  [{:keys [socket closed?]}]
  (reset! closed? true)
  (.close @socket))
