(ns groundwork.parquery.http
  "parquery over HTTP, the server half. A POST to /parquery carries the asks
   a page makes at one moment, as a JSON list; the answer is the list of
   their answers, in the same order (see groundwork.parquery.wire).

   Each ask runs on its own, in parallel, with its own transaction: one
   failing doesn't touch the others. A request carries at most :max-asks
   asks, and across the server at most :max-running run at once; the rest
   wait their turn."
  (:require
   [groundwork.endpoints :as endpoints]
   [groundwork.parquery.wire :as wire]
   [groundwork.sessions :as sessions]
   [groundwork.web :as web])
  (:import
   (java.util.concurrent Callable ExecutionException ExecutorService Future Semaphore)))

(defn- json [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
   :body    (wire/write-json body)})

(defn- run-one
  "One ask, waiting its turn among every ask running on the server."
  [{:keys [endpoints ^Semaphore running]} ask about]
  (.acquire running)
  (try
    (endpoints/encode-answer endpoints (:endpoint ask) (endpoints/call endpoints (merge about ask)))
    (finally (.release running))))

(defn- run-all
  "Every ask's answer, in order: one runs where it is, more run apart."
  [{:keys [^ExecutorService workers]} run asks]
  (if (= 1 (count asks))
    [(run (first asks))]
    (->> (mapv (fn [ask] (.submit workers ^Callable (fn [] (run ask)))) asks)
         (mapv (fn [^Future f]
                 (try (.get f)
                      (catch ExecutionException e (throw (or (.getCause e) e)))))))))

(defn handle
  "The Ring handler for a POST to /parquery."
  [{:keys [settings] :as part} request]
  (let [asks (try (wire/read-json (slurp (:body request) :encoding "UTF-8"))
                  (catch Exception _ ::unreadable))]
    (cond
      (not (and (vector? asks) (seq asks)))
      (json 400 {:error "A list of asks, as JSON"})

      (> (count asks) (:max-asks settings))
      (json 413 {:error (str "At most " (:max-asks settings) " asks in one request")})

      :else
      (let [about {:person       (sessions/person request)
                   :flags        (sessions/flags request)
                   :session-data (sessions/data request)
                   :request-id (web/request-id request)
                   :client-ip  (web/client-ip request)}
            run   (fn [raw]
                    (if-let [ask (and (map? raw) (wire/read-ask raw))]
                      (run-one part ask about)
                      {:status :invalid :errors {:ask ["isn't an ask: {\"endpoint\": …, \"params\": {…}}"]}}))]
        (json 200 (run-all part run asks))))))
