(ns groundwork.web.browser-code.sse
  "sse in the browser: an EventSource. It reopens by itself after a break
   and sends the last message's id, so the server can catch it up."
  (:require
   [groundwork.wire.api :as wire]))

(defn open-sse-line!
  "Opens the server stream at path; (on-message data) for each message, its
   JSON read into data. Returns the line."
  [path on-message]
  (let [source (js/EventSource. path #js {:withCredentials true})]
    (set! (.-onmessage source) (fn [event] (on-message (wire/turn-json-text-into-data (.-data event)))))
    source))

(defn close-sse-line!
  "Closes the line; it doesn't reopen."
  [line]
  (.close line))
