(ns groundwork.transport.server-code.messages
  "Named messages: {:orders/of-shop {:shop/id 7}}. Each name finds its
   handler in the table:

     {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                       :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                       :steps        [find-orders-of-shop add-orders-answer]}}

   The data comes in as JSON data; with a :params-shape it is turned into
   shaped data (real UUIDs, decimals, dates) or answered {:errors …} by key
   before any step runs. The steps run over

     {:groundwork.transport/request      the request
      :groundwork.transport/message-name :orders/of-shop
      :groundwork.transport/message-data {:shop/id #uuid \"…\"}}

   and the answer a step adds goes out as JSON data, by the :answer-shape
   when there is one. Each name runs apart: a failure answers {:error …}
   under its own name."
  (:require
   [groundwork.steps.api :as steps]
   [groundwork.wire.api :as wire]))

(defn- make-context [request message-name message-data]
  {:groundwork.transport/request      request
   :groundwork.transport/message-name message-name
   :groundwork.transport/message-data message-data})

(defn- turn-params-into-shaped-data-or-errors [{:keys [params-shape]} json-data]
  (if params-shape
    (wire/turn-json-data-into-shaped-data-or-errors params-shape json-data)
    {:shaped-data json-data}))

(defn- turn-answer-into-json-data [{:keys [answer-shape]} answer]
  (if answer-shape (wire/turn-shaped-data-into-json-data answer-shape answer) answer))

(defn- run-steps [{handler-steps :steps :as handler} context]
  (let [context (steps/run-steps-until-answered handler-steps context)]
    (if (steps/does-context-have-answer? context)
      (turn-answer-into-json-data handler (:groundwork.steps/answer context))
      {:error "No step answered."})))

(defn- run-handler [handler request message-name json-data]
  (let [{:keys [shaped-data errors]} (turn-params-into-shaped-data-or-errors handler json-data)]
    (if errors
      {:errors errors}
      (run-steps handler (make-context request message-name shaped-data)))))

(defn- run-named-message [handlers request [message-name json-data]]
  [message-name
   (if-let [handler (get handlers message-name)]
     (try (run-handler handler request message-name json-data)
          (catch Exception e {:error (ex-message e)}))
     {:error (str "No handler named " message-name)})])

(defn answer-named-messages
  "The answer to a message: the same names, each with its handler's answer."
  [handlers request message]
  (into {} (map #(run-named-message handlers request %)) message))
