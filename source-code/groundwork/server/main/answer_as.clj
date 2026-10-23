(ns groundwork.server.main.answer-as
  "A message answered as a given asker, off the server's addresses: a
   token's call, on the app's own route, the app having checked the token
   and named its person. It goes through every check a browser's message
   gets (the params' shape, doorman's access and the gates, the answer's
   shape, a write's run-once key) and each handler's :rate-limit, counted
   per the rate-limit key the route gives (the token's, say):

     ;; a :server-to-server route's handler, its tools' :answer-as
     (if-let [person-id (person-for-token pool (bearer-token request))]
       (answer-as {:parts/update-part params}                       ; JSON data, as the browser sends it
                  {:person-id person-id :visitor-id nil :session-flags #{}}
                  {:rate-limit-key (str \"token:\" person-id)})
       {:status 401})
     ;=> {:parts/update-part {…}}                                    ; JSON data, as the browser gets it

   The handlers are made once, at the start, with a step in place of
   doorman's find-session step that reads the asker handed in."
  (:require
   [bridge.api :as bridge]
   [groundwork.server.main.tools :as tools]
   [logbook.api :as logbook]
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def options-shape
  "answer-as's options, every key required. :rate-limit-key: the text
   that tells this caller apart for the handlers' rate limits (a token's,
   \"token:…\")."
  [:map {:closed true}
   [:rate-limit-key [:string {:min 1}]]])

(defn- check [shape what data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " has problems: " (pr-str errors))
                      {:type :groundwork.server.main/bad-answer-as :errors errors}))))
  data)

(def ^:private asker-of-the-call-step
  "In place of doorman's find-session step: the asker handed in, put in
   the context as doorman's step puts it, and in the message's log."
  {:name  :groundwork/asker-of-the-call
   :enter (fn [context]
            (let [{:keys [person-id visitor-id session-flags]} (get-in context [:bridge/request ::asker])]
              (-> context
                  (assoc :doorman/person-id person-id
                         :doorman/visitor-id visitor-id
                         :doorman/session-flags session-flags)
                  (update :bridge/log logbook/with-context
                          (cond-> {} person-id (assoc :person-id person-id) visitor-id (assoc :visitor-id visitor-id))))))})

(defn make-answer-as
  "(fn [message asker options]) from the running app's parts:
   :make-ready-handlers (fn [find-session-step]); :wire and :log the
   app's; :allowed-origin the :sessions settings'; :buckets-before-cleanup
   the :server settings' :rate-limit-buckets-before-cleanup."
  [{:keys [make-ready-handlers wire log allowed-origin buckets-before-cleanup]}]
  (let [ready   (make-ready-handlers asker-of-the-call-step)
        limiter (bridge/make-message-limiter {:find-rate-limit-key    #(::rate-limit-key %)
                                              :buckets-before-cleanup buckets-before-cleanup})]
    (fn [message asker options]
      (check tools/asker-shape "The asker" asker)
      (let [{:keys [rate-limit-key]} (check options-shape "The options" options)]
        (bridge/answer-message ready limiter
                               {:request-method :post :uri "/" :headers {"origin" allowed-origin}
                                ::asker asker ::rate-limit-key rate-limit-key}
                               message
                               {:wire wire :log log})))))
