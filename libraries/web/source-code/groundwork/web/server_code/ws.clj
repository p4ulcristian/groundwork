(ns groundwork.web.server-code.ws
  "ws: a websocket. Both sides send messages, many times a second if they
   need to. For features that need the browser to talk fast; everything
   else is http."
  (:require
   [groundwork.wire.api :as wire]
   [org.httpkit.server :as http-kit]))

(defn make-ring-handler-for-ws
  "A Ring handler that upgrades the request to a websocket. (on-open line),
   (on-message line data) for each message (JSON read into data),
   (on-close line). A request that isn't a websocket gets 400."
  [{:keys [on-open on-message on-close]}]
  (fn [request]
    (if-not (:websocket? request)
      {:status 400 :headers {"content-type" "text/plain"} :body "This address is a websocket."}
      (let [line (atom nil)]
        (http-kit/as-channel
         request
         {:on-open    (fn [channel]
                        (reset! line {:groundwork.web/channel channel :groundwork.web/request request})
                        (when on-open (on-open @line)))
          :on-receive (fn [_channel text]
                        (when on-message (on-message @line (wire/turn-json-text-into-data text))))
          :on-close   (fn [_channel _status]
                        (when on-close (on-close @line)))})))))

(defn send-message-on-ws-line!
  "Sends data as JSON on an open line. Whether the line was still open."
  [line data]
  (http-kit/send! (:groundwork.web/channel line) (wire/turn-data-into-json-text data)))
