(ns groundwork.parquery.http
  "parquery over HTTP, the server half. A POST to /parquery carries the asks
   a page makes at one moment, as a JSON list; the answer is the list of
   their answers, in the same order (see groundwork.parquery.wire).

   Each ask runs on its own, in parallel, with its own transaction: one
   failing doesn't touch the others. A request carries at most :max-asks
   asks, and across the server at most :max-running run at once; the rest
   wait their turn. The :on-answer hook hears every ask's endpoint, answer
   status and time, for counters and alerts.

   A write that names the reads its browser shows (\"watching\") gets back,
   with its answer, the ones its own transaction changed (\"changed\") and the
   tag its bells carry on the websocket: the writer's screen asks again at
   once, without waiting for the websocket, and doesn't ask twice."
  (:require
   [groundwork.endpoints :as endpoints]
   [groundwork.parquery.websocket :as websocket]
   [groundwork.parquery.wire :as wire]
   [groundwork.sessions :as sessions]
   [groundwork.web :as web])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.util.concurrent Callable ExecutionException ExecutorService Future Semaphore)))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.parquery.http"))

(def ^:private max-watching
  "The most reads a write may name as on screen; with more, its answer says
   nothing of what changed and the websocket's bells carry it."
  300)

(defn- json [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
   :body    (wire/write-json body)})

(defn- answered!
  "Tell the :on-answer hook; a hook that throws never breaks the answer."
  [hook ask about answer started]
  (when hook
    (try (hook {:endpoint   (:endpoint ask)
                :status     (:status answer)
                :ms         (/ (- (System/nanoTime) started) 1e6)
                :person     (:person about)
                :request-id (:request-id about)})
         (catch Throwable e
           (.log logger System$Logger$Level/WARNING "The on-answer hook threw" e)))))

(defn- run-one
  "One ask, waiting its turn among every ask running on the server."
  [{:keys [endpoints bells ^Semaphore running settings]} ask about]
  (.acquire running)
  (try
    (let [started (System/nanoTime)
          answer  (endpoints/call endpoints (merge about ask))
          bell    (endpoints/bell answer)]
      (answered! (:on-answer settings) ask about answer started)
      (cond-> (endpoints/encode-answer endpoints (:endpoint ask) answer)
        ;; a write whose browser said what it shows hears which of it changed
        (and bell (:watching ask) (<= (count (:watching ask)) max-watching))
        (assoc :changed (websocket/changed-for bells about (:watching ask) (:rang bell))
               :tag     (:tag bell))))
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
      (let [read    (mapv #(when (map? %) (wire/read-ask %)) asks)
            ;; a write that keeps something for a visitor: someone with no
            ;; session gets one now, and its cookie goes out with the answer
            made    (when (and (nil? (sessions/person request)) (nil? (sessions/visitor request))
                               (some #(:keeps-for-visitor (endpoints/endpoint (:endpoints part) (:endpoint %)))
                                     (remove nil? read)))
                      (sessions/visitor-session! request))
            about   {:person       (sessions/person request)
                     :visitor      (or (:id made) (sessions/visitor request))
                     :flags        (sessions/flags request)
                     :session-data (sessions/data request)
                     :request      request
                     :request-id (web/request-id request)
                     :client-ip  (web/client-ip request)}
            run     (fn [ask]
                      (if ask
                        (run-one part ask about)
                        {:status :invalid :errors {:ask ["isn't an ask: {\"endpoint\": …, \"params\": {…}}"]}}))]
        (cond-> (json 200 (run-all part run read))
          made (assoc-in [:headers "Set-Cookie"] (:cookie made)))))))
