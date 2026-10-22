# groundwork.background-chores

Work that runs outside a request, in Postgres: a queue (a chore is a row),
workers (they claim due chores, run them, retry a failure, and let a chore give
up for good), and schedules (cron lines in UTC that queue a chore at their
times).

Needs groundwork.sql and groundwork.wire. API: `groundwork.background-chores.api`
(`create-tables!`, `add-chore-to-queue!`, `make-give-up-exception`,
`start-workers!`, `stop-workers!`, `list-queued-chores`, `list-dead-chores`,
`run-chore-again-now!`). Its queries are in `sql_files/`; cron lines in
`server-code.cron`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.background-chores.api :as background-chores])

(def send-invoice-email
  {:name         :app.mail/send-invoice-email
   :params-shape [:map [:order/id :int]]
   :run          (fn [{:keys [transaction]} {:order/keys [id]}] …)
   :timeout-ms   60000
   :attempts     5})

(background-chores/create-tables! pool)
(def workers (background-chores/start-workers! pool {:kinds [send-invoice-email] :schedules []
                                                     :workers 4 :poll-ms 1000}))

(background-chores/add-chore-to-queue! pool send-invoice-email {:order/id 42})
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```

With a throwaway Postgres, as in [groundwork.sql's README](../sql/README.md).
A chore that gives up logs its warning during the tests; that is expected.
