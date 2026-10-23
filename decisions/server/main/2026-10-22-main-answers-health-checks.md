# Main answers health checks

> **Changed in part** by [main makes one logbook and passes its log to every library](../../2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md) (2026-10-22): the database's error goes to the app's log, `:warn :house/database-not-ready`.

**Decision:** groundwork.server.main answers two GETs for whoever runs the app (a deploy's proxy, a load balancer, a monitor), as Iron Rainbow's `/health/live` and `/health/ready` do, with their paths and numbers in a required `:health` part of the settings:

```clojure
:health {:live-path                "/health/live"
         :ready-path               "/health/ready"
         :database-timeout-ms      1000   ; ready waits this long for the database, then answers 503
         :not-ready-before-stop-ms 0}     ; stop-app! answers ready 503 this long before the server stops
```

- **live**: 200 `{"status":"live","version":"…"}` while the process answers. It asks nothing of the database.
- **ready**: 200 `{"status":"ready","version":"…"}` when the app has started (`start-app!` has started the server) and the database answers `SELECT 1` within `:database-timeout-ms`; else 503 `{"status":"not-ready","version":"…","reason":"…"}`, the reason one of: the app is starting, the app is stopping, the database did not answer within N ms, the database answered with an error (the error itself goes to the log, not to the asker).
- **Whether the database matches the tables is remembered from the start, not asked again.** `start-app!` refuses a database that doesn't match (or migrates it, in development), so a started app had a matching one.
- Checks that come while one is under way wait on that one: a hung database holds one query and one connection, not one per check.
- **Stopping:** `stop-app!` first marks the app stopping, so ready answers 503, keeps serving for `:not-ready-before-stop-ms`, then stops mailroom's server.
- The version is the settings' `:version`, the one the page and the sse lines carry. Both answers are JSON whatever the request's Accept, never cached (`cache-control: no-store`).
- Both are plain reitit routes beside mailroom's three addresses and the pages: no session, no Origin check (doorman's check runs only inside message handlers, and passes a plain GET anyway), no rate limit (mailroom's limiter counts only messages).

**Why:** a deploy switches traffic to the new version only when it answers ready (Iron Rainbow's Kamal waits for `/health/ready`), and a proxy or monitor needs a liveness answer that doesn't depend on the database. The paths and numbers are settings because they set behaviour ([Every value that sets behaviour is a setting, in main too](../../2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)): which path the proxy asks is the deploy's, and how long ready may wait must be shorter than the proxy's own timeout (Kamal's is 5 s), which only the app knows. Asking whether the database matches the tables every few seconds would read Postgres's catalog each time, and while the deploy migrates for the next version the running one would turn not-ready before the new one is up, so the deploy itself would take the app out of traffic. The wait before the stop exists because http-kit's stop closes the listening socket and the idle connections at once: without it no ready request reaches the stopping app, and a proxy that asks ready learns only from refused connections.

**The price:** the settings grow by a `:health` map of 4 keys. A database that loses the app's tables after the start (dropped by hand) is not seen by ready; the next query fails instead. `stop-app!` takes `:not-ready-before-stop-ms` longer. A check that times out leaves its query running until it ends (the pool's connection wait or `:statement-timeout-ms`); the next checks wait on it.

**Instead of:**
- one `/health` answering both (Iron Rainbow's older route): a proxy can't tell a stuck process from a missing database, and restarting the process doesn't bring the database back
- ready asking `does-database-match-tables?` each time: the catalog read on every check, and a deploy's migration turning the running version not-ready
- fixed paths (`/health/live`, `/health/ready`): a path an app already uses, or a proxy that asks another, would need a code change
- the health routes as mailroom messages: a POST with a JSON body, inside doorman's session and the rate limit; a proxy sends a plain GET
- the database's error text in the answer: the routes are public, and a connection error names hosts and ports
