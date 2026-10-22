# groundwork.background-jobs

Work that runs outside a request, in Postgres: a queue (a job is a row),
workers (they claim due jobs, run them, retry a failure, and let a job give
up for good), and schedules (cron lines in UTC that queue a job at their
times).

Needs groundwork.sql and groundwork.wire. API: `groundwork.background-jobs.api`
(`create-tables!`, `add-job-to-queue!`, `make-give-up-exception`,
`start-workers!`, `stop-workers!`, `list-queued-jobs`, `list-dead-jobs`,
`run-job-again-now!`). Its queries are in `sql_files/`; cron lines in
`server-code.cron`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.background-jobs.api :as background-jobs])

(def send-invoice-email
  {:name         :app.mail/send-invoice-email
   :params-shape [:map [:order/id :int]]
   :run          (fn [{:keys [transaction]} {:order/keys [id]}] …)
   :timeout-ms   60000
   :attempts     5})

(background-jobs/create-tables! pool)
(def workers (background-jobs/start-workers! pool {:kinds [send-invoice-email] :schedules []
                                                   :workers 4 :poll-ms 1000}))

(background-jobs/add-job-to-queue! pool send-invoice-email {:order/id 42})
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```

With a throwaway Postgres, as in [groundwork.sql's README](../sql/README.md).
A job that gives up logs its warning during the tests; that is expected.
