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

(def ^:private stream-response
  {:status  200
   :headers {"content-type"      "text/event-stream"
             "cache-control"     "no-cache"
             ;; nginx and some proxies hold a response back unless told not to
             "x-accel-buffering" "no"}})

(defn- make-line [channel request]
  {:groundwork.web/channel       channel
   :groundwork.web/request       request
   :groundwork.web/last-event-id (get-in request [:headers "last-event-id"])})

(defn- keep-pinging! [channel ping-every-ms]
  (Thread/startVirtualThread
   #(while (do (Thread/sleep (long ping-every-ms))
               (http-kit/send! channel ": ping\n\n" false)))))

(defn- open-line! [request {:keys [on-open ping-every-ms] :or {ping-every-ms 25000}} channel]
  (http-kit/send! channel stream-response false)
  (keep-pinging! channel ping-every-ms)
  (when on-open (on-open (make-line channel request))))

(defn make-ring-handler-for-sse
  "A Ring handler that opens a line and keeps it open: (on-open line) when
   it opens, (on-close line) when it closes."
  [{:keys [on-close] :as options}]
  (fn [request]
    (http-kit/as-channel request
                         {:on-open  #(open-line! request options %)
                          :on-close (fn [channel _status]
                                      (when on-close (on-close (make-line channel request))))})))

(defn turn-message-into-sse-text
  "{:id 17 :data {…}} -> \"id: 17\\ndata: {…}\\n\\n\": data as JSON, one data
   line per line of it."
  [{:keys [id data]}]
  (str (when id (str "id: " id "\n"))
       (str/join (map #(str "data: " % "\n") (str/split-lines (wire/turn-data-into-json-text data))))
       "\n"))

(defn send-message-on-sse-line!
  "Writes one message into an open line. Whether it was still open."
  [line message]
  (http-kit/send! (:groundwork.web/channel line) (turn-message-into-sse-text message) false))
