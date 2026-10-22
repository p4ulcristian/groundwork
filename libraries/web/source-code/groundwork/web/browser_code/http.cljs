(ns groundwork.web.browser-code.http
  "http from the browser: send one request, get its answer. Only sending and
   receiving: keeping answers by question and asking again on a bell is the
   browser-queries library's."
  (:require
   [groundwork.wire.api :as wire]))

(defn send-http-request!
  "Sends {:method :post :path \"/ask\" :data {…}} with the login cookie;
   data travels as JSON. A promise of {:status 200 :data …}, the answer's
   JSON read into data (nil when it has none)."
  [{:keys [method path data]}]
  (-> (js/fetch path (clj->js (cond-> {:method      (.toUpperCase (name (or method :get)))
                                       :credentials "same-origin"
                                       :headers     {"accept" "application/json"}}
                                (some? data) (assoc :body    (wire/turn-data-into-json-text data)
                                                    :headers {"accept"       "application/json"
                                                              "content-type" "application/json"}))))
      (.then (fn [response]
               (-> (.text response)
                   (.then (fn [text]
                            {:status (.-status response)
                             :data   (when (seq text) (wire/turn-json-text-into-data text))})))))))
