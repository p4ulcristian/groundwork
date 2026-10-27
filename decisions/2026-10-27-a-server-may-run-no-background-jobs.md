# A server may run no background jobs: :run-here? false

> Decided by the agent releasing the composer, 2026-10-27, from the user's brief (the switch's settled S2: `:workers` must be at least 1 today; a place where jobs are queued but never run, Iron Rainbow's previews, must be expressible without a fake worker count; fit the composer to how Iron Rainbow did it with `:run-here?`), on groundwork-background-jobs v0.6.2's [A server that runs no workers declares the metrics itself](https://github.com/p4ulcristian/groundwork-background-jobs/blob/main/decisions/2026-10-27-a-server-that-runs-no-workers-declares-the-metrics.md). Since v8.0.0. Changes in part [main takes every library's new settings](2026-10-22-main-takes-every-librarys-new-settings.md) (the `:background-jobs` part).

**Decision:** the `:background-jobs` part is groundwork-background-jobs's settings and `:run-here?`, a boolean, required:

- **true:** the workers start with those settings, running the app's kinds and groundwork's, as before.
- **false:** no workers start; a job the server queues waits for a server that runs them; groundwork-background-jobs's metrics are declared on the app's meter all the same (`declare-background-job-metrics!`, the same kinds), so each queued job is counted and nothing writes `:groundwork-metrics/metric-not-declared`. The running app's `:workers` is nil, and `stop-app!` has none to wait for.

The rest of the part is written out either way: a preview's part is its quiet place's with `:run-here?` changed, as Iron Rainbow's `settings/parts/background-jobs/preview.edn` is.

```clojure
:background-jobs {:run-here? false                    ; a preview, on a copy of the database
                  :workers 4 :poll-ms 5000 …          ; the quiet place's, unread here
                  :metric-buckets {:job-run-seconds [0.05 0.1 0.5 1 5 30 300]}}
```

**Why:** a preview runs on a copy of the database: a job run there would send the copy's emails and pay its payouts. Iron Rainbow says so with `:run-here?` beside the library's settings, and declared the metrics through the library's inner namespace, which the composer may not reach.

**The price:** a server with `:run-here? false` writes worker settings it doesn't use.

**Instead of:**
- **`:workers 0`:** a count that means "none", beside every other worker setting
- **`:background-jobs :not-here` in place of the part:** a preview's part would differ from its quiet place's by every key, not one
