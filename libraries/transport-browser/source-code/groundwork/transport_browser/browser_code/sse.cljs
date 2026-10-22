(ns groundwork.transport-browser.browser-code.sse
  "sse in the browser: an EventSource. It reopens by itself after a break
   and sends the last message's id, so the server can catch it up."
  (:require
   [groundwork.transport-browser.browser-code.messages :as messages]
   [groundwork.transport-browser.browser-code.shapes :as shapes]
   [groundwork.wire.api :as wire]))

(defn open-sse-line!
  "Opens the server stream; each named message, shaped by its :answer-shape,
   goes to its handler. Returns the line."
  [address shapes-by-name handlers]
  (doto (js/EventSource. address #js {:withCredentials true})
    (set! -onmessage #(->> (wire/turn-json-text-into-data (.-data %))
                           (shapes/turn-answer-into-shaped-data shapes-by-name)
                           (messages/hand-named-messages! handlers)))))

(defn close-sse-line!
  "Closes the line; it doesn't reopen."
  [line]
  (.close line))
