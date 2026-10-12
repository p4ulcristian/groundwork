(ns groundwork.parquery.http
  "parquery over HTTP, the browser half. The asks made in the same moment
   travel in one POST to /parquery (at most :max-asks, more go in more
   requests); each answer comes back to its own callback, typed by its
   endpoint's answer shape.

     (http/set-shapes! {:jobs/list {:input [:map …] :answer [:vector job]} …})
     (http/ask! {:endpoint :jobs/list :params {:shop/id id}} (fn [answer] …))

   An answer is {:status :ok :data …} or another status (see
   groundwork.parquery.wire); a request that never reached the server gives
   {:status :error :message …} to each of its asks."
  (:require
   [groundwork.parquery.wire :as wire]))

(defonce ^:private shapes (atom {}))
(defonce ^:private max-asks (atom 50))

(defn set-shapes!
  "The endpoints' shapes, {endpoint {:input … :answer …}}, shared with the
   server (a .cljc file), so values keep their types both ways."
  [m]
  (reset! shapes m))

(defn set-max-asks!
  "The most asks one request carries; the server's :max-asks."
  [n]
  (reset! max-asks n))

(defn- encode-params [{:keys [endpoint params]}]
  (if-let [input (get-in @shapes [endpoint :input])]
    (wire/encode input params)
    params))

(defn form
  "An ask as it travels, for comparing with what the server sends back:
   [\"jobs/list\" {:shop/id \"…\"}]."
  [{:keys [endpoint] :as ask}]
  [(wire/endpoint-name endpoint) (wire/read-json (wire/write-json (or (encode-params ask) {})))])

(defn- decode-answer [endpoint {:keys [status data] :as answer}]
  (let [answer (assoc answer :status (keyword status))]
    (if-let [shape (and (= "ok" status) (get-in @shapes [endpoint :answer]))]
      (let [{:keys [value errors]} (wire/decode shape data)]
        (if errors
          {:status :error :message "The answer didn't fit its shape" :errors errors}
          (assoc answer :data value)))
      answer)))

(defn- fetch-transport
  "POST the asks' JSON to /parquery; on-text gets the answer's text,
   on-failure a message."
  [body on-text on-failure]
  (-> (js/fetch "/parquery" #js {:method      "POST"
                                 :credentials "same-origin"
                                 :headers     #js {"Content-Type" "application/json"}
                                 :body        body})
      (.then (fn [response]
               (if (.-ok response)
                 (.then (.text response) on-text)
                 (on-failure (str "The server answered " (.-status response))))))
      (.catch (fn [_] (on-failure "No connection to the server")))))

(defonce ^{:doc "How a request travels; tests replace it."} transport (atom fetch-transport))

(defonce ^:private waiting (atom []))

(defn- send-batch! [batch]
  (@transport (wire/write-json (mapv (fn [{:keys [ask]}] (wire/ask (assoc ask :params (encode-params ask)))) batch))
              (fn [text]
                (let [answers (wire/read-json text)]
                  (doseq [[{:keys [ask on-answer]} answer] (map vector batch answers)]
                    (on-answer (decode-answer (:endpoint ask) answer)))))
              (fn [message]
                (doseq [{:keys [on-answer]} batch]
                  (on-answer {:status :error :message message})))))

(defn- send-waiting! []
  (let [batch @waiting]
    (reset! waiting [])
    (doseq [part (partition-all @max-asks batch)]
      (send-batch! (vec part)))))

(defn ask!
  "Ask an endpoint; on-answer gets its answer. Asks made in the same moment
   share one request. A write carries its one-time :key."
  [ask on-answer]
  (swap! waiting conj {:ask ask :on-answer on-answer})
  (when (= 1 (count @waiting))
    (js/setTimeout send-waiting! 0)))
