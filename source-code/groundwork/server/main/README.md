# groundwork.server.main

The server's one start: `start-app!` takes the app as one map (settings,
tables, changes, scope kinds, handlers, pages, background job kinds,
schedules), checks the settings (each library brings its part; every problem
at once), migrates in development or refuses an unsafe or old database in
production, and starts the pool, the handlers (doorman's check, then sync's notes),
sync's listener, backroom's background job workers, in development
showroom's watcher of the browser build, and mailroom's server (its
`:server` settings: address, the three message addresses, the ping, the
proxy's header, the stop wait, the ws reopen delay, the rate-limit cleanup
threshold; askers told apart by session, else address; sse hooks for sync,
the ws hooks nil). `migrate-app!` is for the deploy, as the owner. The page
shell answers every page address: the app's scripts and styles, and the
browser's settings (version, addresses, ws reopen delay, pages) as JSON in
the page.

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
through sync to every open sse line, as only its table, and each person's
asking again through the gates to only their own shop's orders.
