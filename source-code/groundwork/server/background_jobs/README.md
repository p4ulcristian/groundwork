# groundwork.server.background-jobs

Work that runs outside a request, in Postgres: a queue (a background job is a row),
workers (they claim due background jobs, run them, retry a failure, and let a background job give
up for good), and schedules (cron lines in UTC that queue a background job at their
times).

Needs groundwork.server.database and wire. API: `groundwork.server.background-jobs.api`
(`background-jobs-tables`, `add-background-job-to-queue!`, `make-give-up-exception`,
`start-workers!`, `stop-workers!`, `list-queued-background-jobs`, `list-dead-background-jobs`,
`run-background-job-again-now!`). Its queries are in `sql_files/`; cron lines in
`server-code.cron`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.server.background-jobs.api :as background-jobs])

(def send-invoice-email
  {:name         :app.mail/send-invoice-email
   :params-shape [:map [:order/id :int]]
   :run          (fn [{:keys [transaction]} {:order/keys [id]}] …)
   :timeout-ms   60000
   :attempts     5})

(database/migrate! pool (into app-tables background-jobs/background-jobs-tables) changes)
(def workers (background-jobs/start-workers! pool {:kinds [send-invoice-email] :schedules []
                                                     :workers 4 :poll-ms 1000}))

(background-jobs/add-background-job-to-queue! pool send-invoice-email {:order/id 42})
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```

With a throwaway Postgres, as in [groundwork.server.database's README](../sql/README.md).
A background job that gives up logs its warning during the tests; that is expected.
