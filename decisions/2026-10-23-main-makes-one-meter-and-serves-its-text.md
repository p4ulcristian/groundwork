# main makes one meter, hands it to every library, and serves its text

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): meter is groundwork-metrics; its settings and its route's are one `:metrics` section, the route's under `:route`.

> The user's decision, 2026-10-23 (meter v0.1.2; cellar v0.4, backroom v0.5, mailroom v0.7 and doorbell v0.5 count on the app's meter). Builds on [main makes one logbook and passes its log to every library](2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md) and meter's [A library takes the meter as :meter](https://github.com/p4ulcristian/meter/blob/main/decisions/2026-10-23-a-library-takes-the-meter-as-meter.md).

**Decision:**

- **One meter**, made by `start-app!` from the `:meter` settings (meter's `settings-shape`, every key required: `:jvm-metrics`, `:most-series-per-metric`), with the app's log, and handed under `:meter` to cellar's `start-pool!`, mailroom's `make-routes-for-messages`, doorbell's `start-listening!` (with the ready tables, `:tables`) and backroom's `start-workers!`. No library makes its own. The running app holds it, `:meter`, for the app's own counting (backroom's `add-background-job-to-queue!` takes it).
- **`:most-series-per-metric` covers the largest declaration**: mailroom's `messages_total`, (handlers+1)×24; its `answer_seconds`, (handlers+1)×(buckets+3); cellar's `query_seconds`, (queries+1)×(buckets+3), the handlers counting house's two and the queries every library's. meter refuses a bigger declaration at the start.
- **The histograms' buckets are settings** where each library takes them: `:database :metric-buckets`, `:server :metric-buckets`, `:background-jobs :metric-buckets`.
- **house's own metrics** (`house.server.main.metrics`): `house_stage{stage}` (1 for the stage the app is in, read from the health's stage), `house_ready_checks_total{outcome}` (each ready check, `ready` or `not-ready`), `house_build_info{version}` (1, under `:version`).
- **logbook's counts are read, not counted**: `logbook_lines_total{level}`, `logbook_write_failures_total` and `logbook_errors_kept` are declared by house with a `:read` of `logbook/read-counts`, under logbook's names, since logbook counts into no meter itself.
- **The text is one more GET**, `meter/make-metrics-routes` at the `:metrics` settings' `:path`, only with its `:token` as a bearer token, beside health and errors: outside sessions, the Origin check and rate limits.
- **`migrate-app!`'s owner pool counts on a meter of its own**, never served, `:jvm-metrics false`: cellar takes one meter per pool, and the deploy's run has no `/metrics`.

```clojure
:meter   {:jvm-metrics true :most-series-per-metric 5000}
:metrics {:path "/metrics" :token "…"}   ; kept out of the code, as a secret
```

**Why:** one meter is one `/metrics` for Prometheus, as one logbook is one stream of lines; each library knows its own names and label sets, house knows the stage and the version. Reading logbook's counts when the text is written keeps logbook free of meter (meter's own log is a logbook). The token keeps request rates and query names off the open internet.

**The price:** every library's metrics are in one meter, so `:most-series-per-metric` is sized by the largest of them, and a new handler or query can push a declaration over it (the start refuses, naming it). The owner pool's metrics are thrown away.

**Instead of:**
- a meter per library, each served: several addresses for one app, and one more setting each
- logbook counting into the meter: a circle, meter's log being a logbook
- the metrics behind the session: Prometheus has no session; a bearer token is what its scrape config carries
