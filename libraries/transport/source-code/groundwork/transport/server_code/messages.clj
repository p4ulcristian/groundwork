(ns groundwork.transport.server-code.messages
  "Named messages: {:orders/of-shop {:shop/id 7}}. Each name finds its
   handler in the table and runs its steps over

     {:groundwork.transport/request      the request
      :groundwork.transport/message-name :orders/of-shop
      :groundwork.transport/message-data {:shop/id 7}}

   The answer a step adds is the answer under that name. Each name runs
   apart: a failure answers {:error …} under its own name."
  (:require
   [groundwork.steps.api :as steps]))

(defn- make-context [request message-name message-data]
  {:groundwork.transport/request      request
   :groundwork.transport/message-name message-name
   :groundwork.transport/message-data message-data})

(defn- run-handler [{handler-steps :steps} context]
  (let [context (steps/run-steps-until-answered handler-steps context)]
    (if (steps/does-context-have-answer? context)
      (:groundwork.steps/answer context)
      {:error "No step answered."})))

(defn- run-named-message [handlers request [message-name message-data]]
  (let [handler (get handlers message-name)]
    [message-name
     (if-not handler
       {:error (str "No handler named " message-name)}
       (try (run-handler handler (make-context request message-name message-data))
            (catch Exception e {:error (ex-message e)})))]))

(defn answer-named-messages
  "The answer to a message: the same names, each with its handler's answer."
  [handlers request message]
  (into {} (map #(run-named-message handlers request %)) message))
