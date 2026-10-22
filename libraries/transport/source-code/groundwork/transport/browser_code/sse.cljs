(ns groundwork.transport.browser-code.sse
  "sse in the browser: an EventSource. It reopens by itself after a break
   and sends the last message's id, so the server can catch it up."
  (:require
   [groundwork.transport.browser-code.messages :as messages]
   [groundwork.wire.api :as wire]))

(defn open-sse-line!
  "Opens the server stream; each named message goes to its handler. Returns
   the line."
  [address handlers]
  (doto (js/EventSource. address #js {:withCredentials true})
    (set! -onmessage #(messages/hand-named-messages! handlers (wire/turn-json-text-into-data (.-data %))))))

(defn close-sse-line!
  "Closes the line; it doesn't reopen."
  [line]
  (.close line))
