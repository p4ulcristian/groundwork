(ns groundwork.transport-server.server-code.messages
  "Named messages: {:orders/of-shop {:shop/id 7}}. Each name finds its
   handler in the table:

     {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                       :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                       :steps        [find-orders-of-shop add-orders-answer]}}

   The data comes in as JSON data; with a :params-shape it is turned into
   shaped data (real UUIDs, decimals, dates) or answered {:errors …} by key
   before any step runs. The steps run over

     {:groundwork.transport-server/request      the request
      :groundwork.transport-server/message-name :orders/of-shop
      :groundwork.transport-server/message-data {:shop/id #uuid \"…\"}}

   and the answer a step adds goes out as JSON data, by the :answer-shape
   when there is one. Each name runs apart: a failure answers {:error …}
   under its own name, and its details go to the log only."
  (:require
   [groundwork.steps.api :as steps]
   [groundwork.wire.api :as wire])
  (:import
   (java.lang System$Logger System$Logger$Level)))

(defn- make-context [request message-name message-data]
  {:groundwork.transport-server/request      request
   :groundwork.transport-server/message-name message-name
   :groundwork.transport-server/message-data message-data})

(defn- turn-params-into-shaped-data-or-errors [{:keys [params-shape]} json-data]
  (if params-shape
    (wire/turn-json-data-into-shaped-data-or-errors params-shape json-data)
    {:shaped-data json-data}))

(def ^:private answer-doesnt-fit
  {:error "The answer doesn't fit its shape."})

(defn- turn-answer-into-json-data
  "The answer as JSON data by its :answer-shape, checked against it with
   every map closed: an answer with a key the shape doesn't name is never
   sent (an extra column, a password hash)."
  [{:keys [answer-shape]} answer]
  (cond
    (not answer-shape) answer
    (wire/does-data-fit-shape? (wire/turn-shape-into-closed-shape answer-shape) answer)
    (wire/turn-shaped-data-into-json-data answer-shape answer)
    :else answer-doesnt-fit))

(defn- run-steps-for-answer [handler-steps context]
  (let [context (steps/run-steps-until-answered handler-steps context)]
    (if (steps/does-context-have-answer? context)
      (:groundwork.steps/answer context)
      {:error "No step answered."})))

(defn- is-only-an-error?
  "{:error \"…\"} and nothing else: Groundwork's own refusal, sent as it is."
  [answer]
  (and (map? answer) (= #{:error} (set (keys answer)))))

(defn- run-steps
  "The handler's steps over the context. A handler's :run-steps-in, when it
   has one, is handed the running of them, to run them inside what it sets
   up (a transaction): (run-steps-in run context)."
  [{handler-steps :steps :keys [run-steps-in] :as handler} context]
  (let [run    #(run-steps-for-answer handler-steps %)
        answer (if run-steps-in (run-steps-in run context) (run context))]
    (if (is-only-an-error? answer) answer (turn-answer-into-json-data handler answer))))

(defn- run-handler [handler request message-name json-data]
  (let [{:keys [shaped-data errors]} (turn-params-into-shaped-data-or-errors handler json-data)]
    (if errors
      {:errors errors}
      (run-steps handler (make-context request message-name shaped-data)))))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.transport-server"))

(def ^:private went-wrong {:error "Something went wrong."})

(defn- run-named-message
  "A failure is logged with its details and answered with a plain error:
   a database's message never reaches the browser."
  [handlers request [message-name json-data]]
  [message-name
   (if-let [handler (get handlers message-name)]
     (try (run-handler handler request message-name json-data)
          (catch Exception e
            (.log logger System$Logger$Level/ERROR (str "Handler " message-name " failed") ^Throwable e)
            went-wrong))
     {:error (str "No handler named " message-name)})])

(defn answer-named-messages
  "The answer to a message: the same names, each with its handler's answer."
  [handlers request message]
  (into {} (map #(run-named-message handlers request %)) message))
