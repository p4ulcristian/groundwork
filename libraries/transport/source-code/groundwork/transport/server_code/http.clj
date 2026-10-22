(ns groundwork.transport.server-code.http
  "http: a request and its one answer. A route's work is a vector of steps
   over {:groundwork.transport/request request}; the answer a step adds is the
   Ring response."
  (:require
   [groundwork.steps.api :as steps]))

(def ^:private no-answer
  {:status  500
   :headers {"content-type" "text/plain"}
   :body    "No step answered."})

(defn make-ring-handler-from-steps
  "A Ring handler that runs the steps and responds with the answer a step
   added, or 500 when none did."
  [steps]
  (fn [request]
    (let [context (steps/run-steps-until-answered steps {:groundwork.transport/request request})]
      (if (steps/does-context-have-answer? context)
        (:groundwork.steps/answer context)
        no-answer))))
