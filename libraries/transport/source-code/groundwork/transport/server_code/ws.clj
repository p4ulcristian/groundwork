(ns groundwork.transport.server-code.ws
  "ws: a websocket. Both sides send messages, many times a second if they
   need to. For features that need the browser to talk fast; everything
   else is http."
  (:require
   [groundwork.wire.api :as wire]
   [org.httpkit.server :as http-kit]))

(def ^:private not-a-websocket
  {:status 400 :headers {"content-type" "text/plain"} :body "This address is a websocket."})

(defn- make-line [channel request]
  {:groundwork.transport/channel channel :groundwork.transport/request request})

(defn- call-with-line [callback request]
  (fn [channel & more]
    (when callback (apply callback (make-line channel request) more))))

(defn- open-websocket [request {:keys [on-open on-message on-close]}]
  (http-kit/as-channel request
                       {:on-open    (call-with-line on-open request)
                        :on-receive (fn [channel text]
                                      (when on-message
                                        (on-message (make-line channel request) (wire/turn-json-text-into-data text))))
                        :on-close   (fn [channel _status]
                                      (when on-close (on-close (make-line channel request))))}))

(defn make-ring-handler-for-ws
  "A Ring handler that upgrades the request to a websocket: (on-open line),
   (on-message line data) for each message (its JSON read into data),
   (on-close line). A request that isn't a websocket gets 400."
  [options]
  (fn [request]
    (if (:websocket? request)
      (open-websocket request options)
      not-a-websocket)))

(defn send-message-on-ws-line!
  "Sends data as JSON on an open line. Whether it was still open."
  [line data]
  (http-kit/send! (:groundwork.transport/channel line) (wire/turn-data-into-json-text data)))
