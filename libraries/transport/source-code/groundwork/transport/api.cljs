(ns groundwork.transport.api
  "The browser half of groundwork.transport: named messages over http, sse
   and ws. The server half is api.clj.

     (transport/send-message! {:orders/of-shop {:shop/id 7}})        ; a promise of the answer
     (transport/open-sse-line! {:bells/changed on-bell})              ; messages from the server
     (def line (transport/open-ws-line! {:cursors/moved on-cursor}))
     (transport/send-message-on-ws-line! line {:cursors/move {:x 10 :y 20}})"
  (:require
   [groundwork.transport.browser-code.http :as http]
   [groundwork.transport.browser-code.sse :as sse]
   [groundwork.transport.browser-code.ws :as ws]))

(def default-addresses
  "Where the three pipes are, the same as on the server."
  {:http "/groundwork/http" :sse "/groundwork/sse" :ws "/groundwork/ws"})

(defn send-message!
  "http: POSTs a named message; a promise of the answer, keyed by the same names."
  ([message] (send-message! (:http default-addresses) message))
  ([address message] (http/send-message! address message)))

(defn open-sse-line!
  "sse: opens the stream; each named message goes to its handler by name.
   It reopens by itself after a break."
  ([handlers] (open-sse-line! (:sse default-addresses) handlers))
  ([address handlers] (sse/open-sse-line! address handlers)))

(defn close-sse-line!
  "sse: closes the line; it doesn't reopen."
  [line]
  (sse/close-sse-line! line))

(defn open-ws-line!
  "ws: opens the websocket; each named message, answers included, goes to
   its handler by name. It reopens a second after a break."
  ([handlers] (open-ws-line! (:ws default-addresses) handlers))
  ([address handlers] (ws/open-ws-line! address handlers)))

(defn send-message-on-ws-line!
  "ws: sends a named message on the line. Whether it was open."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn close-ws-line!
  "ws: closes the line; it doesn't reopen."
  [line]
  (ws/close-ws-line! line))
