(ns groundwork.server.main.run-once
  "Every write runs once, by its one-time key.

   The browser (parlor's :parlor/send, through groundwork's browser start) sends
   each write with a one-time key, a random UUID made for that send and
   kept across its retries, in the request's idempotency-key header. A
   handler whose action may write (doorman's does-action-only-read? says
   no) gets its steps wrapped in one step, :groundwork/run-once, which runs
   inside the handler's own transaction, after doorman's check:

   - it claims the key for the message's name in groundwork.run_once_keys
     (claim_once_key.sql). Claimed now: the steps run, and their answer is
     kept with the key, in the same transaction; if the transaction rolls
     back, so does the claim, and a repeat runs again.
   - Claimed before by the same person: the steps don't run; the kept
     answer is the answer (a kept refusal too), and nothing is saved.
     Claimed before by someone else: refused,
     :groundwork/once-key-of-someone-else, and nothing runs.
   - A repeat that comes while the first is still running waits for it
     (Postgres holds the claim until the first transaction ends), then
     answers the first answer; if the first rolled back, the repeat runs.
   - A key older than :keep-keys-ms is forgotten: a repeat that late runs
     again. A background job of groundwork's, on the :forget-keys cron line,
     deletes the forgotten keys.

   A write without a key runs as it is, every time (a message on ws, a
   test's answer-message); a key that isn't a UUID's text is refused,
   :groundwork/once-key-not-a-uuid. Both reasons are groundwork's own
   (refusals): put-run-once-into-handlers adds them to every write's
   :refusals, so the app doesn't write them, and a handler that declares
   one itself is refused.

   And every write, keyed or not, commits only when its answer fits its
   :answer-shape: one that doesn't throws inside the transaction, so
   nothing is saved and the asker is answered :bridge/failed (the details
   in the log, under its request id), as when any step throws.

   A handler that only reads is left as it is: no key is claimed, whatever
   the request carries."
  (:require
   [cellar.api :as database]
   [doorman.api :as doorman]
   [bridge.api :as bridge]
   [wire.api :as wire]))

(def key-header
  "The request header a write's one-time key travels in, as Ring names
   it: the name of the IETF's Idempotency-Key header, lowercase."
  "idempotency-key")

(def run-once-keys-table
  "The one-time keys, in groundwork's own folder: one row per key and message
   name, who claimed it, the answer its write gave (JSON text by the
   app's wire; NULL when the steps gave none), and when."
  {:table     :groundwork/run-once-keys
   :columns   [[:run-once-key/key          :uuid         {:primary-key? true}]
               [:run-once-key/message-name :string       {:primary-key? true}]
               [:run-once-key/person-id    :uuid         {:optional? true}]
               [:run-once-key/answer       :string       {:optional? true}]
               [:run-once-key/created-at   :time/instant {:default :now}]]
   :indexes   [{:columns [[:run-once-key/created-at :ascending :nulls-last]]
                :method :btree :ops {} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

(def ^:private claim-once-key (database/make-query-from-sql-file "groundwork/server/main/claim_once_key.sql"))
(def ^:private find-once-key (database/make-query-from-sql-file "groundwork/server/main/find_once_key.sql"))
(def ^:private update-once-key-answer (database/make-query-from-sql-file "groundwork/server/main/update_once_key_answer.sql"))
(def ^:private delete-old-once-keys (database/make-query-from-sql-file "groundwork/server/main/delete_old_once_keys.sql"))

(def refusals
  "groundwork's own reasons, which the run-once step refuses a write with,
   each with its data's shape (nil: none): :groundwork/once-key-not-a-uuid,
   the idempotency-key header isn't a UUID's text;
   :groundwork/once-key-of-someone-else, another asker claimed the key
   first. Each needs its text in the app's catalogs, as bridge's and
   doorman's do."
  {:groundwork/once-key-not-a-uuid        nil
   :groundwork/once-key-of-someone-else   nil})

(defn- turn-message-name-into-text [message-name]
  (subs (str message-name) 1))

(defn- turn-into-json-data-or-throw [wire shape data handler-name what]
  (let [{:keys [json-data errors]} (wire/turn-shaped-data-into-json-data-or-errors wire shape data)]
    (when errors
      (throw (ex-info (str handler-name "'s " what " doesn't fit its shape; nothing is saved")
                      {:type :groundwork.server.main/write-answer-doesnt-fit :handler handler-name :errors errors})))
    json-data))

(defn- check-answer-fits!
  "A write's answer, as JSON data by its shape; one that doesn't fit throws,
   so the transaction rolls back. A refusal is kept as its reason's text
   and its data as JSON data by the reason's shape (nil for nil); one
   whose reason the handler doesn't declare is left as it is, for bridge
   to answer :bridge/failed."
  [wire {:keys [answer-shape refusals]} handler-name answer]
  (if (bridge/is-error-answer? answer)
    (let [{reason :bridge/refused data :bridge/data} answer]
      (if (and (contains? refusals reason) (some? (get refusals reason)))
        {:bridge/refused (subs (str reason) 1)
         :bridge/data    (turn-into-json-data-or-throw wire (get refusals reason) data handler-name (str reason "'s data"))}
        {:bridge/refused (subs (str reason) 1) :bridge/data data}))
    (turn-into-json-data-or-throw wire answer-shape answer handler-name "answer")))

(defn- turn-kept-text-into-answer
  "The kept answer as the steps gave it: a refusal, its reason a keyword
   again and its data by the reason's shape; data by the answer shape."
  [wire {:keys [answer-shape refusals]} handler-name text]
  (let [data   (wire/turn-json-text-into-data wire text)
        shaped (fn [shape json-data what]
                 (let [{:keys [shaped-data errors]} (wire/turn-json-data-into-shaped-data-or-errors wire shape json-data)]
                   (when errors
                     (throw (ex-info (str handler-name "'s kept " what " doesn't fit its shape now")
                                     {:type :groundwork.server.main/kept-answer-doesnt-fit :handler handler-name :errors errors})))
                   shaped-data))]
    (if (bridge/is-error-answer? data)
      (let [reason (keyword (:bridge/refused data))
            shape  (get refusals reason)]
        {:bridge/refused reason
         :bridge/data    (if (some? shape) (shaped shape (:bridge/data data) "refusal's data") (:bridge/data data))})
      (shaped answer-shape data "answer"))))

(defn- run-and-check
  "The handler's steps, and its answer checked against its shape: the
   context and the answer as JSON data, nil when no step answered."
  [handler-steps handler handler-name context]
  (let [context (bridge/run-steps-until-answered handler-steps context)]
    [context (when (bridge/does-context-have-answer? context)
               (check-answer-fits! (:bridge/wire context) handler handler-name (:bridge/answer context)))]))

(defn- answer-from-kept-row
  "A repeat: the first answer, when the same person claimed the key."
  [context handler handler-name {:run-once-key/keys [person-id answer]}]
  (cond
    (not= person-id (:doorman/person-id context)) (bridge/refuse context :groundwork/once-key-of-someone-else nil)
    (nil? answer)                                 context
    :else (bridge/add-answer-to-context
           context (turn-kept-text-into-answer (:bridge/wire context) handler handler-name answer))))

(defn- run-keyed [handler-steps handler handler-name keep-keys-ms once-key context]
  (let [transaction (:doorman/transaction context)
        row-key     {:run-once-key/key          once-key
                     :run-once-key/message-name (turn-message-name-into-text (:bridge/message-name context))}]
    (if (database/find-one-row transaction claim-once-key
                               (assoc row-key
                                      :run-once-key/person-id (:doorman/person-id context)
                                      :keep-keys-ms keep-keys-ms))
      (let [[context json-data] (run-and-check handler-steps handler handler-name context)]
        (when (bridge/does-context-have-answer? context)
          (database/run-statement! transaction update-once-key-answer
                                   (assoc row-key :run-once-key/answer
                                          (wire/turn-data-into-json-text (:bridge/wire context) json-data))))
        context)
      (answer-from-kept-row context handler handler-name
                            (database/find-one-row transaction find-once-key row-key)))))

(defn- make-run-once-step
  "The one step a write's steps become (see the namespace); handler with
   groundwork's reasons in its :refusals."
  [handler-name {handler-steps :steps :as handler} keep-keys-ms]
  {:name  :groundwork/run-once
   :enter (fn [context]
            (let [header (get-in context [:bridge/request :headers key-header])]
              (cond
                (nil? header)
                (first (run-and-check handler-steps handler handler-name context))

                (parse-uuid header)
                (run-keyed handler-steps handler handler-name keep-keys-ms (parse-uuid header) context)

                :else
                (bridge/refuse context :groundwork/once-key-not-a-uuid nil))))})

(defn- refuse-handlers-declaring-refusals!
  "A handler that declares one of groundwork's reasons itself is refused:
   groundwork adds them, as doorman adds its own."
  [handlers]
  (let [doubled (for [[handler-name handler] handlers
                      reason (keys refusals)
                      :when (contains? (:refusals handler) reason)]
                  (str handler-name " declares " reason ", groundwork's own: it is added to every write"))]
    (when (seq doubled)
      (throw (ex-info (str "The handlers have problems: " (pr-str (vec doubled)))
                      {:type :groundwork.server.main/bad-handlers :problems (vec doubled)})))))

(defn put-run-once-into-handlers
  "The app's handlers, each whose :action may write (doorman's
   does-action-only-read? says no) with its steps wrapped in the run-once
   step and groundwork's reasons (refusals) added to its :refusals; the
   ones that only read, and any without an :action (doorman refuses
   those), as they are. A handler declaring one of groundwork's reasons
   itself is refused, :groundwork.server.main/bad-handlers. Before
   doorman's put-access-into-handlers, which puts its check in front and
   the transaction around."
  [handlers scope-kinds keep-keys-ms]
  (refuse-handlers-declaring-refusals! handlers)
  (into {}
        (map (fn [[handler-name {:keys [action] :as handler}]]
               [handler-name
                (if (and action (not (doorman/does-action-only-read? scope-kinds action)))
                  (let [handler (update handler :refusals merge refusals)]
                    (assoc handler :steps [(make-run-once-step handler-name handler keep-keys-ms)]))
                  handler)]))
        handlers))

(def forget-keys-system-actor
  "The named system actor (doorman's) the forgetting runs as: the job's
   one transaction is doorman's run-as-system! as it (backroom's
   :system-actor)."
  :groundwork-once-keys)

(defn make-forget-keys-kind
  "groundwork's background job kind that forgets the keys kept longer than
   :keep-keys-ms, from the :run-once settings, run as
   forget-keys-system-actor; its time limit and attempts are the
   :forget-keys settings'."
  [{:keys [keep-keys-ms] {:keys [timeout-ms attempts]} :forget-keys}]
  {:name         :groundwork/forget-old-once-keys
   ;; groundwork's own actor: it deletes from groundwork.run_once_keys, a table without
   ;; sentences, which any system actor reaches; no app sentence names it
   :system-actor forget-keys-system-actor
   :params-shape [:map {:closed true}]
   :run          (fn [{:keys [transaction]} _params]
                   (database/run-statement! transaction delete-old-once-keys {:keep-keys-ms keep-keys-ms}))
   :timeout-ms   timeout-ms
   :attempts     attempts})

(defn make-forget-keys-schedule
  "The kind's schedule, on the :forget-keys :cron line (UTC)."
  [run-once-settings]
  {:kind (make-forget-keys-kind run-once-settings) :params {} :cron (get-in run-once-settings [:forget-keys :cron])})
