(ns groundwork.transport.api
  "The browser half of groundwork.transport: its side of http, sse and ws. The
   server half is api.clj."
  (:require
   [groundwork.transport.browser-code.http :as http]
   [groundwork.transport.browser-code.sse :as sse]
   [groundwork.transport.browser-code.ws :as ws]))

(defn send-http-request!
  "http: sends {:method :post :path \"/ask\" :data {…}} with the login
   cookie, data as JSON. A promise of {:status 200 :data …}."
  [request]
  (http/send-http-request! request))

(defn open-sse-line!
  "sse: opens the server stream at path; (on-message data) for each
   message. It reopens by itself after a break. Returns the line."
  [path on-message]
  (sse/open-sse-line! path on-message))

(defn close-sse-line!
  "sse: closes the line; it doesn't reopen."
  [line]
  (sse/close-sse-line! line))

(defn open-ws-line!
  "ws: opens a websocket at path; (on-message data) for each message. It
   reopens a second after a break. Returns the line."
  [path on-message]
  (ws/open-ws-line! path on-message))

(defn send-message-on-ws-line!
  "ws: sends data as JSON on the line. Whether it was open."
  [line data]
  (ws/send-message-on-ws-line! line data))

(defn close-ws-line!
  "ws: closes the line; it doesn't reopen."
  [line]
  (ws/close-ws-line! line))
