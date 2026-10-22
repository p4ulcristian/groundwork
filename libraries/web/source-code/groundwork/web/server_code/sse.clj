(ns groundwork.web.server-code.sse
  "sse: a server stream (Server-Sent Events). The browser opens one GET per
   tab; the answer stays open, and each message is a few lines written into
   it. A comment line every :ping-every-ms keeps proxies from cutting it.

     id: 17
     data: {\"table\":\"orders\",\"ids\":[\"42\"]}

   A browser that reconnects sends the last id it got as Last-Event-ID; the
   line has it as :groundwork.web/last-event-id, to catch up from."
  (:require
   [clojure.string :as str]
   [groundwork.wire.api :as wire]
   [org.httpkit.server :as http-kit]))

(def ^:private stream-headers
  {"content-type"      "text/event-stream"
   "cache-control"     "no-cache"
   ;; nginx and some proxies hold a response back unless told not to
   "x-accel-buffering" "no"})

(defn- keep-pinging! [channel ping-every-ms]
  (Thread/startVirtualThread
   (fn []
     (loop []
       (Thread/sleep (long ping-every-ms))
       (when (http-kit/send! channel ": ping\n\n" false)
         (recur))))))

(defn make-ring-handler-for-sse
  "A Ring handler that opens a line and keeps it open. (on-open line) when it
   opens, (on-close line) when it closes; a line is
   {:groundwork.web/channel … :groundwork.web/request … :groundwork.web/last-event-id …}."
  [{:keys [on-open on-close ping-every-ms] :or {ping-every-ms 25000}}]
  (fn [request]
    (let [line (atom nil)]
      (http-kit/as-channel
       request
       {:on-open  (fn [channel]
                    (http-kit/send! channel {:status 200 :headers stream-headers} false)
                    (reset! line {:groundwork.web/channel       channel
                                  :groundwork.web/request       request
                                  :groundwork.web/last-event-id (get-in request [:headers "last-event-id"])})
                    (keep-pinging! channel ping-every-ms)
                    (when on-open (on-open @line)))
        :on-close (fn [_channel _status]
                    (when (and on-close @line) (on-close @line)))}))))

(defn send-message-on-sse-line!
  "Writes one message into an open line: data as JSON, with an id the
   browser sends back as Last-Event-ID when it reconnects. Whether the line
   was still open."
  [line {:keys [id data]}]
  (http-kit/send! (:groundwork.web/channel line)
                  (str (when id (str "id: " id "\n"))
                       (str/join (for [text-line (str/split-lines (wire/turn-data-into-json-text data))]
                                   (str "data: " text-line "\n")))
                       "\n")
                  false))
