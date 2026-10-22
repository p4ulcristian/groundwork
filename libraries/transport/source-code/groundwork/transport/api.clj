(ns groundwork.transport.api
  "How the browser and the server talk: the server, and three ways over it.
   The api of groundwork.transport; its browser half is api.cljs.

     http  a request and its one answer: all data travels this way
     sse   a server stream: one answer per tab that stays open, for
           \"something changed\" messages (bells)
     ws    a websocket: both sides talk fast, for features that need it

   transport knows no addresses: the server serves one Ring handler, and
   groundwork.routes makes it from the routes. A line (sse or ws) is
   {:groundwork.transport/channel … :groundwork.transport/request …}."
  (:require
   [groundwork.transport.server-code.http :as http]
   [groundwork.transport.server-code.server :as server]
   [groundwork.transport.server-code.sse :as sse]
   [groundwork.transport.server-code.ws :as ws]
   [org.httpkit.server :as http-kit]))

(defn start-server!
  "Starts http-kit serving one Ring handler, every request on its own
   virtual thread. {:ip \"0.0.0.0\" :port 8080}; port 0 picks a free one.
   Returns the running server: {:port the-port …}."
  [ring-handler settings]
  (server/start-server! ring-handler settings))

(defn stop-server!
  "Stops taking requests, lets running ones finish (up to 10 s), then stops."
  [server]
  (server/stop-server! server))

(defn make-ring-handler-from-steps
  "http: a Ring handler that runs the steps over
   {:groundwork.transport/request request} and responds with the answer a
   step added (a Ring response map), or 500 when no step answered."
  [steps]
  (http/make-ring-handler-from-steps steps))

(defn make-ring-handler-for-sse
  "sse: a Ring handler that opens a line and keeps it open:
   {:on-open (fn [line]) :on-close (fn [line]) :ping-every-ms 25000}."
  [options]
  (sse/make-ring-handler-for-sse options))

(defn make-ring-handler-for-ws
  "ws: a Ring handler that upgrades to a websocket:
   {:on-open (fn [line]) :on-message (fn [line data]) :on-close (fn [line])}.
   A request that isn't a websocket gets 400."
  [options]
  (ws/make-ring-handler-for-ws options))

(defn send-message-on-sse-line!
  "sse: writes {:id … :data …} into an open line; data travels as JSON, and
   the id comes back as Last-Event-ID when the browser reconnects. Whether
   the line was still open."
  [line message]
  (sse/send-message-on-sse-line! line message))

(defn send-message-on-ws-line!
  "ws: sends data as JSON on an open line. Whether it was still open."
  [line data]
  (ws/send-message-on-ws-line! line data))

(defn is-line-open?
  "Whether an sse or ws line is still open."
  [line]
  (http-kit/open? (:groundwork.transport/channel line)))
