(ns groundwork.access.server-code.check
  "The check: every handler declares its :action (and its shapes), and
   Groundwork puts the check in front of its steps; the app doesn't write it.

     {:orders/one {:action       :orders/see
                   :record       [:orders :order/id]     ; the row the message names, by this param
                   :params-shape [:map [:order/id :uuid]]
                   :answer-shape …
                   :steps        [find-order add-order-answer]}}

   Each message then runs: find the session; in one transaction, tell
   Postgres who is asking (groundwork.asking); the check: may this person do
   the action on the record? Not: \"Not found.\", and no step runs; yes: the
   handler's steps, with :groundwork.access/transaction in the context."
  (:require
   [clojure.string :as str]
   [groundwork.schema.api :as schema]
   [groundwork.sql.api :as sql]
   [groundwork.steps.api :as steps]
   [groundwork.wire.api :as wire]))

(def ^:private not-found {:error "Not found."})

(def ^:private set-asking (sql/make-query-from-sql-file "groundwork/access/sql_files/set_asking.sql"))

(defn- find-problems-of-handler [conditions [handler-name {:keys [action record params-shape answer-shape]}]]
  (concat
   (when-not action [(str handler-name " has no :action")])
   (when-not params-shape [(str handler-name " has no :params-shape")])
   (when-not answer-shape [(str handler-name " has no :answer-shape")])
   (when (and record (not (get-in conditions [(first record) :actions action])))
     [(str handler-name ": no sentence on " (first record) " allows " action)])))

(defn check-handlers
  "Throws with every problem of every handler at once."
  [handlers conditions]
  (let [problems (mapcat #(find-problems-of-handler conditions %) handlers)]
    (when (seq problems)
      (throw (ex-info (str "The handlers have problems:\n  " (str/join "\n  " problems))
                      {:type :groundwork.access/bad-handlers :problems (vec problems)})))))

(defn- find-primary-key-column [tables table-key]
  (some (fn [{:keys [table columns]}]
          (when (= table table-key)
            (some (fn [[k _ options]] (when (:primary-key? options) (schema/turn-key-into-column-name k))) columns)))
        tables))

(defn- make-record-query [{:keys [tables conditions]} {:keys [action] [table-key] :record}]
  (sql/make-query-from-sql-text
   (str "may " action " on " table-key)
   (str "SELECT EXISTS (SELECT 1 FROM " (schema/turn-table-key-into-table-name table-key)
        " WHERE " (find-primary-key-column tables table-key) " = :record/id"
        " AND (" (get-in conditions [table-key :actions action]) ")) AS allowed")))

(defn- make-check-step [setup {:keys [record] :as handler}]
  (let [query (when record (make-record-query setup handler))]
    {:name  :groundwork.access/check
     :enter (fn [context]
              (if (or (nil? query)
                      (:allowed (sql/find-one-row (:groundwork.access/transaction context) query
                                                  {:record/id (get-in context [:groundwork.transport/message-data (second record)])})))
                context
                (steps/add-answer-to-context context not-found)))}))

(defn- turn-actions-into-asking [person-id actions-by-scope]
  (wire/turn-data-into-json-text
   {:person  (some-> person-id str)
    :holding (reduce (fn [holding [[kind scope-id] actions]]
                       (reduce #(update %1 (str (name kind) " " (subs (str %2) 1)) (fnil conj []) (str scope-id))
                               holding actions))
                     {} actions-by-scope)}))

(defn- make-run-steps-in [{:keys [pool find-session-step list-actions]}]
  (fn [run context]
    (let [context (steps/run-steps-until-answered [find-session-step] context)]
      (if (steps/does-context-have-answer? context)
        (:groundwork.steps/answer context)
        (sql/run-in-transaction! pool
          (fn [transaction]
            (let [person-id (:groundwork.sessions/person-id context)
                  actions   (if person-id (list-actions transaction person-id) {})]
              (sql/find-one-row transaction set-asking {:asking (turn-actions-into-asking person-id actions)})
              (run (assoc context :groundwork.access/transaction transaction)))))))))

(defn put-access-into-handlers
  "Every handler checked, then given the check in front of its steps and the
   running of them in a transaction that knows who is asking."
  [handlers setup]
  (check-handlers handlers (:conditions setup))
  (let [run-steps-in (make-run-steps-in setup)]
    (update-vals handlers
                 (fn [handler]
                   (-> handler
                       (update :steps #(into [(make-check-step setup handler)] %))
                       (assoc :run-steps-in run-steps-in))))))
