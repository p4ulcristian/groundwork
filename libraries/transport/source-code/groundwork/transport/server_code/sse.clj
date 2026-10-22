(ns groundwork.transport.server-code.sse
  "sse: a server stream (Server-Sent Events) at /groundwork/sse. The browser
   opens one GET per tab; the answer stays open, and each named message is a
   few lines written into it. A comment line every :ping-every-ms keeps proxies from cutting it.

     id: 17
     data: {\"bells/changed\":{\"table\":\"orders\",\"ids\":[\"42\"]}}

   A browser that reconnects sends the last id it got as Last-Event-ID; the
   line has it as :groundwork.transport/last-event-id, to catch up from."
  (:require
   [clojure.string :as str]
   [groundwork.wire.api :as wire]
   [org.httpkit.server :as http-kit]))

(def ^:private stream-response
  {:status  200
   :headers {"content-type"      "text/event-stream"
             "cache-control"     "no-cache"
             ;; nginx and some proxies hold a response back unless told not to
             "x-accel-buffering" "no"}})

(defn- make-line [channel request]
  {:groundwork.transport/channel       channel
   :groundwork.transport/request       request
   :groundwork.transport/last-event-id (get-in request [:headers "last-event-id"])})

(defn- keep-pinging! [channel ping-every-ms]
  (Thread/startVirtualThread
   #(while (do (Thread/sleep (long ping-every-ms))
               (http-kit/send! channel ": ping\n\n" false)))))

(defn- open-line! [request {:keys [on-sse-open ping-every-ms] :or {ping-every-ms 25000}} channel]
  (http-kit/send! channel stream-response false)
  (keep-pinging! channel ping-every-ms)
  (when on-sse-open (on-sse-open (make-line channel request))))

(defn make-ring-handler-for-sse
  "A Ring handler that opens a line and keeps it open: (on-sse-open line)
   when it opens, (on-sse-close line) when it closes."
  [{:keys [on-sse-close] :as options}]
  (fn [request]
    (http-kit/as-channel request
                         {:on-open  #(open-line! request options %)
                          :on-close (fn [channel _status]
                                      (when on-sse-close (on-sse-close (make-line channel request))))})))

(defn turn-message-into-sse-text
  "A named message, with an optional id, as the lines of one sse event:
   \"id: 17\\ndata: {…}\\n\\n\"."
  [message id]
  (str (when id (str "id: " id "\n"))
       (str/join (map #(str "data: " % "\n") (str/split-lines (wire/turn-data-into-json-text message))))
       "\n"))

(defn send-message-on-sse-line!
  "Writes a named message into an open line; the id, if given, comes back
   as Last-Event-ID when the browser reconnects. Whether it was still open."
  ([line message] (send-message-on-sse-line! line message nil))
  ([line message id]
   (http-kit/send! (:groundwork.transport/channel line) (turn-message-into-sse-text message id) false)))
