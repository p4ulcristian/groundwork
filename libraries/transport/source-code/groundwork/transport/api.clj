(ns groundwork.transport.api
  "How the browser and the server talk: named messages over three pipes.
   The api of groundwork.transport; its browser half is api.cljs.

     http  /groundwork/http  a POST of a message, answered at once
     sse   /groundwork/sse   the server stream: messages to the browser
     ws    /groundwork/ws    a websocket: messages both ways

   A message is a map of names to data, {:orders/of-shop {:shop/id 7}}. The
   server answers it from one handler table, by name, whichever pipe it came
   on; the answer has the same names:

     (def handlers {:orders/of-shop {:steps [find-orders-of-shop add-orders-answer]}})

     (transport/start-server!
      (routes/make-ring-handler-from-routes (transport/make-routes-for-messages handlers {}))
      {:ip \"0.0.0.0\" :port 8080})

   A line (an open sse or ws connection) is
   {:groundwork.transport/channel … :groundwork.transport/request …}."
  (:require
   [groundwork.transport.server-code.http :as http]
   [groundwork.transport.server-code.server :as server]
   [groundwork.transport.server-code.sse :as sse]
   [groundwork.transport.server-code.ws :as ws]
   [org.httpkit.server :as http-kit]))

(def default-addresses
  "Where the three pipes are, unless the settings say otherwise."
  {:http "/groundwork/http" :sse "/groundwork/sse" :ws "/groundwork/ws"})

(defn make-routes-for-messages
  "The three routes, as reitit data, for groundwork.routes to serve.
   Options: :addresses (default-addresses), :on-sse-open, :on-sse-close,
   :on-ws-open, :on-ws-close (each (fn [line])), :ping-every-ms (25000)."
  [handlers {:keys [addresses] :or {addresses default-addresses} :as options}]
  [[(:http addresses) {:post {:handler (http/make-ring-handler-for-http handlers)}}]
   [(:sse addresses)  {:get  {:handler (sse/make-ring-handler-for-sse options)}}]
   [(:ws addresses)   {:get  {:handler (ws/make-ring-handler-for-ws handlers options)}}]])

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

(defn send-message-on-sse-line!
  "Writes a named message into an open sse line; an id comes back as
   Last-Event-ID when the browser reconnects. Whether the line was open."
  ([line message] (sse/send-message-on-sse-line! line message))
  ([line message id] (sse/send-message-on-sse-line! line message id)))

(defn send-message-on-ws-line!
  "Sends a named message on an open ws line. Whether it was open."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn is-line-open?
  "Whether an sse or ws line is still open."
  [line]
  (http-kit/open? (:groundwork.transport/channel line)))
