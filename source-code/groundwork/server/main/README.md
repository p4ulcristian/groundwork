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

API: `groundwork.server.main.api` (`settings-shape`, `page-settings-shape`,
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
asking again through the gates to only their own shop's orders.
