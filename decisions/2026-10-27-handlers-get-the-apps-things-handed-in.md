# Handlers get the app's things handed in, as background jobs do

> **Changed in part** by [An app has several file storages, each with its own upload route or none](2026-10-31-an-app-has-several-file-storages-each-with-its-own-upload.md) (2026-10-31): the part `:file-storage` is `:file-storages`, the app's storages by name.

> Decided by the agent releasing the composer, 2026-10-27, from the user's brief (the switch's settled H1: a handler's steps get a transaction, the asker and the log, but no pool, no `run-as-system!`, no job queue, no settings, no meter; design it in the composer's style, one required app key, a map the app gives, handed into every handler's context, as background jobs do with `:given-to-every-run`). Since v8.0.0. Replaces the app map's `:given-to-every-background-job-run` (v5.0.0).

**Decision:** the app map has one required key, `:make-given`, `(fn [parts] map)`. The composer calls it once at the start, after the pool, the file storage and the mailer are made and before the handlers are, with the running app's parts, every key there:

| part | what |
|---|---|
| `:settings` | the checked settings |
| `:pool` | the app's pool, its own role (owns nothing; a statement no transaction says the asker of asks as nobody: the gates give it the open rows and the ungated tables only) |
| `:run-as-system!` | `(fn [system-actor {:keys [read-only? log]} f])`, groundwork-access's `run-as-system!` on the pool |
| `:add-background-job-to-queue!` | `(fn [pool-or-transaction kind params {:keys [run-in-ms key]}])`, groundwork-background-jobs's, on the app's shaper and meter |
| `:shaper` `:meter` `:texts` | the app's one shaper, meter and catalogs |
| `:file-storage` `:mailer` | the app's one file storage and mailer |

The map it returns is:

- in **every app handler's steps' context at `:groundwork/given`**: a step of the composer's, first among the handler's own steps, puts it there (groundwork-access's check, the daily caps' step and a write's run-once step come before it, its transaction around them);
- **every background job run's `:given`** (groundwork-background-jobs's `:given-to-every-run`), the app's kinds and the composer's;
- **the running app's `:given`**, for the app's own routes and tests.

The same map each time. Anything but a map refuses the start, `:groundwork.server.main/bad-app`. An app with nothing to give writes `(fn [_parts] {})`.

```clojure
:make-given (fn [{:keys [pool run-as-system! add-background-job-to-queue! meter]}]
              {:settings app-settings :pool pool :run-as-system! run-as-system! :meter meter
               :job-queue {:add! add-background-job-to-queue!} :stripe stripe-client})

{:name :orders/confirm
 :enter (fn [{:groundwork/keys [given] :as context}]
          ((get-in given [:job-queue :add!]) (:groundwork-access/transaction context) send-confirmation {:order/id id} {:run-in-ms 0 :key nil})
          …)}
```

**Why:** what an app's code needs at run time is made by the composer at the start (the pool, the meter, the mailer), so a map the app writes before the start can't hold it; a function of the running parts can, and the app names its keys as its code reads them (Iron Rainbow's jobs already read `(:job-queue given)`, `(:mailer given)`, `(:settings given)`). Handlers and jobs get one map: the same things, made once, one place to look.

**The price:** `:given-to-every-background-job-run` is gone; an app that gave a map writes `(fn [_parts] that-map)`. A handler holds the pool and `run-as-system!`, so a step can step outside its asker's gates; that is what they are for (a system actor's write the asker may not do), and the sentences still hold the system actor.

**Instead of:**
- **the composer's parts at fixed context keys (`:groundwork/pool`, `:groundwork/meter`, …) and the app's static map beside:** two places, and the jobs' `:given`, groundwork-background-jobs's one key, couldn't hold the parts without mixing them into the app's map
- **two keys, one for handlers and one for jobs:** the same things made twice
- **a map the app writes, as before:** it can't hold what the composer makes at the start
