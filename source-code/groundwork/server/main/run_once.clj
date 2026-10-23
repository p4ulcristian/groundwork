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
     answer is the answer, and nothing is saved. Claimed before by someone
     else: refused, and nothing runs.
   - A repeat that comes while the first is still running waits for it
     (Postgres holds the claim until the first transaction ends), then
     answers the first answer; if the first rolled back, the repeat runs.
   - A key older than :keep-keys-ms is forgotten: a repeat that late runs
     again. A background job of groundwork's, on the :forget-keys cron line,
     deletes the forgotten keys.

   A write without a key runs as it is, every time (a message on ws, a
   test's answer-message); a key that isn't a UUID's text is refused.

   And every write, keyed or not, commits only when its answer fits its
   :answer-shape: one that doesn't throws inside the transaction, so
   nothing is saved and the asker is answered \"Something went wrong.\"
   (the details in the log), as when any step throws.

   A handler that only reads is left as it is: no key is claimed, whatever
   the request carries."
  (:require
   [cellar.api :as database]
   [doorman.api :as doorman]
   [mailroom.api :as mailroom]
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
   :indexes   [[:run-once-key/created-at]]
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

(def ^:private claim-once-key (database/make-query-from-sql-file "groundwork/server/main/claim_once_key.sql"))
(def ^:private find-once-key (database/make-query-from-sql-file "groundwork/server/main/find_once_key.sql"))
(def ^:private update-once-key-answer (database/make-query-from-sql-file "groundwork/server/main/update_once_key_answer.sql"))
(def ^:private delete-old-once-keys (database/make-query-from-sql-file "groundwork/server/main/delete_old_once_keys.sql"))

(def ^:private key-not-a-uuid {:error "The one-time key must be a UUID."})

(def ^:private key-of-someone-else {:error "This one-time key was used by someone else."})

(defn- turn-message-name-into-text [message-name]
  (subs (str message-name) 1))

(defn- check-answer-fits!
  "A write's answer, as JSON data by its shape; one that doesn't fit throws,
   so the transaction rolls back. An error answer passes as it is, as
   mailroom sends it."
  [wire answer-shape handler-name answer]
  (if (mailroom/is-error-answer? answer)
    answer
    (let [{:keys [json-data errors]} (wire/turn-shaped-data-into-json-data-or-errors wire answer-shape answer)]
      (when errors
        (throw (ex-info (str handler-name "'s answer doesn't fit its :answer-shape; nothing is saved")
                        {:type :groundwork.server.main/write-answer-doesnt-fit :handler handler-name :errors errors})))
      json-data)))

(defn- turn-kept-text-into-answer
  "The kept answer as the steps gave it: an error answer as it is, data by
   the answer shape."
  [wire answer-shape handler-name text]
  (let [data (wire/turn-json-text-into-data wire text)]
    (if (mailroom/is-error-answer? data)
      data
      (let [{:keys [shaped-data errors]} (wire/turn-json-data-into-shaped-data-or-errors wire answer-shape data)]
        (when errors
          (throw (ex-info (str handler-name "'s kept answer doesn't fit its :answer-shape now")
                          {:type :groundwork.server.main/kept-answer-doesnt-fit :handler handler-name :errors errors})))
        shaped-data))))

(defn- run-and-check
  "The handler's steps, and its answer checked against its shape: the
   context and the answer as JSON data, nil when no step answered."
  [handler-steps answer-shape handler-name context]
  (let [context (mailroom/run-steps-until-answered handler-steps context)]
    [context (when (mailroom/does-context-have-answer? context)
               (check-answer-fits! (:mailroom/wire context) answer-shape handler-name (:mailroom/answer context)))]))

(defn- answer-from-kept-row
  "A repeat: the first answer, when the same person claimed the key."
  [context answer-shape handler-name {:run-once-key/keys [person-id answer]}]
  (cond
    (not= person-id (:doorman/person-id context)) (mailroom/add-answer-to-context context key-of-someone-else)
    (nil? answer)                                 context
    :else (mailroom/add-answer-to-context
           context (turn-kept-text-into-answer (:mailroom/wire context) answer-shape handler-name answer))))

(defn- run-keyed [handler-steps answer-shape handler-name keep-keys-ms once-key context]
  (let [transaction (:doorman/transaction context)
        row-key     {:run-once-key/key          once-key
                     :run-once-key/message-name (turn-message-name-into-text (:mailroom/message-name context))}]
    (if (database/find-one-row transaction claim-once-key
                               (assoc row-key
                                      :run-once-key/person-id (:doorman/person-id context)
                                      :keep-keys-ms keep-keys-ms))
      (let [[context json-data] (run-and-check handler-steps answer-shape handler-name context)]
        (when (mailroom/does-context-have-answer? context)
          (database/run-statement! transaction update-once-key-answer
                                   (assoc row-key :run-once-key/answer
                                          (wire/turn-data-into-json-text (:mailroom/wire context) json-data))))
        context)
      (answer-from-kept-row context answer-shape handler-name
                            (database/find-one-row transaction find-once-key row-key)))))

(defn- make-run-once-step
  "The one step a write's steps become (see the namespace)."
  [handler-name {handler-steps :steps :keys [answer-shape]} keep-keys-ms]
  {:name  :groundwork/run-once
   :enter (fn [context]
            (let [header (get-in context [:mailroom/request :headers key-header])]
              (cond
                (nil? header)
                (first (run-and-check handler-steps answer-shape handler-name context))

                (parse-uuid header)
                (run-keyed handler-steps answer-shape handler-name keep-keys-ms (parse-uuid header) context)

                :else
                (mailroom/add-answer-to-context context key-not-a-uuid))))})

(defn put-run-once-into-handlers
  "The app's handlers, each whose :action may write (doorman's
   does-action-only-read? says no) with its steps wrapped in the run-once
   step; the ones that only read, and any without an :action (doorman
   refuses those), as they are. Before doorman's put-access-into-handlers,
   which puts its check in front and the transaction around."
  [handlers scope-kinds keep-keys-ms]
  (into {}
        (map (fn [[handler-name {:keys [action] :as handler}]]
               [handler-name
                (if (and action (not (doorman/does-action-only-read? scope-kinds action)))
                  (assoc handler :steps [(make-run-once-step handler-name handler keep-keys-ms)])
                  handler)]))
        handlers))

(defn make-forget-keys-kind
  "groundwork's background job kind that forgets the keys kept longer than
   :keep-keys-ms, from the :run-once settings; its time limit and attempts
   are the :forget-keys settings'."
  [{:keys [keep-keys-ms] {:keys [timeout-ms attempts]} :forget-keys}]
  {:name         :groundwork/forget-old-once-keys
   :params-shape [:map {:closed true}]
   :run          (fn [{:keys [transaction]} _params]
                   (database/run-statement! transaction delete-old-once-keys {:keep-keys-ms keep-keys-ms}))
   :timeout-ms   timeout-ms
   :attempts     attempts})

(defn make-forget-keys-schedule
  "The kind's schedule, on the :forget-keys :cron line (UTC)."
  [run-once-settings]
  {:kind (make-forget-keys-kind run-once-settings) :params {} :cron (get-in run-once-settings [:forget-keys :cron])})
