# groundwork.server.main

The server's one start: `start-app!` takes the app as one map (settings,
tables, changes, scope kinds, handlers, pages, background job kinds,
schedules), checks the settings (each library brings its part; every problem
at once), migrates in development or refuses an unsafe or old database in
production, and starts the pool, the handlers (doorman's check, then doorbell's notes),
doorbell's listener, backroom's background job workers, in development
showroom's watcher of the browser build, and mailroom's server (its
`:server` settings, mailroom's, every key required; askers told apart by
session, else address; sse hooks for doorbell, the ws hooks nil; files from
`:page :files`). `migrate-app!` is for the deploy, as the owner. The page
shell answers every page address: the app's scripts and styles, and the
browser's settings (version, addresses, sse and ws reopen delays, the
question store's `:doorbell` settings, pages) as
JSON in the page; its viewport, referrer policy, frame options and content
security policy are `:page` settings. Every value that sets behaviour is a
setting; what stays fixed, and why:
[the decision](../../../../decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md).

Two plain GETs answer whoever runs the app (a deploy's proxy, a load
balancer), outside sessions, the Origin check and rate limits, at the
`:health` settings' paths, as JSON with the `:version`: live, 200 while the
process answers, no database; ready, 200 once `start-app!` has started the
server while the database answers `SELECT 1` within `:database-timeout-ms`,
else 503 with a reason. That the database matches the tables is not asked
again (the start refused a database that didn't). `stop-app!` answers ready
with 503 for `:not-ready-before-stop-ms` before the server stops:
[the decision](../../../../decisions/server/main/2026-10-22-main-answers-health-checks.md).

```clojure
:health {:live-path "/health/live" :ready-path "/health/ready"
         :database-timeout-ms 1000 :not-ready-before-stop-ms 0}
```

API: `groundwork.server.main.api` (`settings-shape`, `page-settings-shape`,
`health-settings-shape`,
`check-settings`, `groundwork-tables`, `make-ready-tables`, `migrate-app!`,
`start-app!`, `stop-app!`). Decisions:
[`decisions/server/main`](../../../../decisions/server/main/README.md).

```clojure
(def running (main/start-app! {:settings settings :tables [shops orders] :changes [] :scope-kinds scope-kinds
                               :handlers handlers :pages pages :background-job-kinds [] :schedules []}))
(main/stop-app! running)
```

Its test starts the whole server as production does (`migrate-app!` as the
owner, `start-app!` as a role that owns nothing) and follows a new order
through doorbell to every open sse line, as only its table, and each person's
asking again through the gates to only their own shop's orders; then the health checks: live
and ready without origin or session; ready 503 at its timeout while every
connection is held, 503 while the app's role can't log in, 200 again after
each; 503 while stopping.
