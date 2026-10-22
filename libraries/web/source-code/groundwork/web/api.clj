(ns groundwork.web.api
  "The web server and the three ways the browser and the server talk. The
   api of groundwork.web; its browser half is api.cljs.

     http  a request and its one answer: all data travels this way
     sse   a server stream: one answer per tab that stays open, for
           \"something changed\" messages (bells)
     ws    a websocket: both sides talk fast, for features that need it

   Routes are reitit data, and each says its kind:

     [[\"/orders/:id\" {:get {:handler (web/make-ring-handler-from-steps [find-order add-order-answer])}}]
      [\"/bells\"      {:sse {:on-open remember-line! :on-close forget-line!}}]
      [\"/cursors\"    {:ws  {:on-open … :on-message … :on-close …}}]]

   Ring at the edge: the server calls one Ring handler, so any Ring
   middleware works around it. A line (sse or ws) is
   {:groundwork.web/channel … :groundwork.web/request … }."
  (:require
   [groundwork.web.server-code.http :as http]
   [groundwork.web.server-code.routes :as routes]
   [groundwork.web.server-code.server :as server]
   [groundwork.web.server-code.sse :as sse]
   [groundwork.web.server-code.ws :as ws]
   [org.httpkit.server :as http-kit]))

(defn make-ring-handler-from-steps
  "http: a Ring handler that runs the steps over
   {:groundwork.web/request request} and responds with the answer a step
   added (a Ring response map), or 500 when no step answered."
  [steps]
  (http/make-ring-handler-from-steps steps))

(defn make-ring-handler-from-routes
  "The one Ring handler from routes of all three kinds. No route: 404; a
   method the route doesn't have: 405."
  [routes]
  (routes/make-ring-handler-from-routes routes))

(defn start-server!
  "Starts http-kit serving the Ring handler, every request on its own
   virtual thread. {:ip \"0.0.0.0\" :port 8080}; port 0 picks a free one.
   Returns the running server: {:port the-port …}."
  [ring-handler settings]
  (server/start-server! ring-handler settings))

(defn stop-server!
  "Stops taking requests, lets running ones finish (up to 10 s), then stops."
  [server]
  (server/stop-server! server))

(defn send-message-on-sse-line!
  "sse: writes {:id … :data …} into an open line; data travels as JSON, and
   the id comes back as Last-Event-ID when the browser reconnects. Whether
   the line was still open."
  [line message]
  (sse/send-message-on-sse-line! line message))

(defn send-message-on-ws-line!
  "ws: sends data as JSON on an open line. Whether the line was still open."
  [line data]
  (ws/send-message-on-ws-line! line data))

(defn is-line-open?
  "Whether an sse or ws line is still open."
  [line]
  (http-kit/open? (:groundwork.web/channel line)))
