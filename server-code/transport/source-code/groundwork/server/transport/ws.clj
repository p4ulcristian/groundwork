(ns groundwork.server.transport.ws
  "ws: a websocket at /groundwork/ws. Each message the browser sends is
   answered by the same handlers as http, and the answer sent back on the
   line; the server can also send on a line by itself."
  (:require
   [groundwork.server.transport.messages :as messages]
   [groundwork.server.wire.api :as wire]
   [org.httpkit.server :as http-kit]))

(def ^:private not-a-websocket
  {:status 400 :headers {"content-type" "text/plain"} :body "This address is a websocket."})

(defn- make-line [channel request]
  {:groundwork.server.transport/channel channel :groundwork.server.transport/request request})

(defn send-message-on-ws-line!
  "Sends a named message as JSON on an open line. Whether it was still open."
  [line message]
  (http-kit/send! (:groundwork.server.transport/channel line) (wire/turn-data-into-json-text message)))

(defn- answer-on-line! [handlers line text]
  (let [message (wire/turn-json-text-into-data text)]
    (send-message-on-ws-line! line (messages/answer-named-messages handlers (:groundwork.server.transport/request line) message))))

(defn- open-websocket [request handlers {:keys [on-ws-open on-ws-close]}]
  (http-kit/as-channel request
                       {:on-open    #(when on-ws-open (on-ws-open (make-line % request)))
                        :on-receive #(answer-on-line! handlers (make-line %1 request) %2)
                        :on-close   (fn [channel _status]
                                      (when on-ws-close (on-ws-close (make-line channel request))))}))

(defn make-ring-handler-for-ws
  "A Ring handler that upgrades to a websocket answering named messages.
   A request that isn't a websocket gets 400."
  [handlers options]
  (fn [request]
    (if (:websocket? request)
      (open-websocket request handlers options)
      not-a-websocket)))
