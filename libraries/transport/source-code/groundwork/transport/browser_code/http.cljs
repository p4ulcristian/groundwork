(ns groundwork.transport.browser-code.http
  "http from the browser: send one request, get its answer. Only sending and
   receiving: keeping answers by question and asking again on a bell is the
   browser-queries library's."
  (:require
   [groundwork.wire.api :as wire]))

(defn- make-fetch-options [{:keys [method data]}]
  (clj->js (cond-> {:method      (.toUpperCase (name (or method :get)))
                    :credentials "same-origin"
                    :headers     {"accept" "application/json"}}
             (some? data) (-> (assoc :body (wire/turn-data-into-json-text data))
                              (assoc-in [:headers "content-type"] "application/json")))))

(defn- read-answer [response]
  (.then (.text response)
         (fn [text]
           {:status (.-status response)
            :data   (when (seq text) (wire/turn-json-text-into-data text))})))

(defn send-http-request!
  "Sends {:method :post :path \"/ask\" :data {…}} with the login cookie;
   data travels as JSON. A promise of {:status 200 :data …}, the answer's
   JSON read into data (nil when it has none)."
  [{:keys [path] :as request}]
  (.then (js/fetch path (make-fetch-options request)) read-answer))
