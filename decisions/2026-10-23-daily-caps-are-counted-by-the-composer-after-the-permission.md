# Daily caps are counted by the composer, in Postgres, after the permission check

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P8: 53 handlers cap a person per day; `:feedback/send-email` has three limits). Built on tidy-up, not released.

**What was wrong:** bridge had one `:rate-limit` per handler, a token bucket in this server's memory: a deploy reset it, each server counted on its own, and it was checked before doorman's permission, so people who may not call a handler could spend a cap shared by everyone.

**Decision:** a handler writes out its daily caps beside bridge's per-minute limit, `[]` for none:

```clojure
:rate-limit {:max 3 :per-ms 60000}                              ; bridge's: bursts, in memory
:daily-caps [{:max 10 :per :asker} {:max 300 :per :everyone}]   ; the composer's: budgets, in Postgres
```

- **Counted by the composer**, in its own table (`groundwork.daily_cap_counts`, beside its run-once keys), by a step right after doorman's check and before the handler's steps: one upsert per capped call, in its own statement.
- **A day is a UTC date.** **Every allowed call counts** (the safe side for mail and money), whether or not the handler's work commits.
- **Over a cap:** `:bridge/too-many {:retry-after-ms …}`, the time until midnight UTC.
- `:per` is `:asker` (the person, visitor or token's person) or `:everyone`.

**Why:** exact across servers and deploys, and only people allowed to call a handler spend its shared budget. Only the composer knows both who is allowed (doorman) and the database (cellar).

**The price:** one more key on every handler, and one statement per capped call.

**Instead of:**
- **bridge's `:rate-limit` as a list with a database hook**: bridge, the transport, would count before the permission
- **each app counting in its own steps**: written again for every capped handler
- **doorman counting**: doorman answers "may they", not "how many today"

**Settled while building:**

1. **Over a cap the reason is groundwork's own, `:groundwork/daily-cap-reached {:retry-after-ms n}`, not `:bridge/too-many`**: bridge refuses a handler that declares one of its own reasons and answers `:bridge/failed` to a step that refuses with an undeclared one, so a step can't give `:bridge/too-many`. groundwork adds its reason to every capped handler's `:refusals`, the browser start to every name's, and it needs its text in every catalog, as the run-once reasons do. Answering `:bridge/too-many` waits on bridge letting a step refuse with it.
2. **The counts run on a pool of their own**, `:daily-caps {:pool-size n}` (the `:database` settings at that size, its metrics on a meter of its own, not served): the step runs inside doorman's transaction, and a second connection of the app's pool, asked for while holding the first, could leave every connection waiting for another under load.
3. **Exact, and a refused call spends nothing**: the day's rows of the call's caps are made at 0 and locked, in order of whom they count (`lock_daily_cap_count.sql`), then all are counted together only when each is below its `:max` (`add_to_daily_cap_count.sql`), in one transaction of the counting pool that commits at once. So an asker over their own cap spends nothing of everyone's. Two statements per cap, not one upsert.
4. **The step is the first of the handler's own steps, inside a write's run-once step**: a repeat answered from its one-time key isn't counted again; the day and the wait are taken from one `Instant` on the server.
5. **`:asker` is the person (a token's person too), else the visitor, else the request's address** (bridge's `find-client-address`, the `:server` settings' header), "nobody" with no request (none of groundwork's doors). Two caps of one `:per` share a row; the lower `:max` holds.
6. **The table is `groundwork.daily_cap_counts`** (message name, whom it counts, the day; the count), doorbell silent; groundwork's background job `:groundwork/forget-old-daily-cap-counts`, run as its system actor `:groundwork-daily-caps` on the `:daily-caps :forget-counts` cron line, deletes the days before today.
7. **A handler without `:daily-caps`, or with a bad one, refuses the start**, `:groundwork.server.main/bad-handlers`, every problem at once; groundwork's own handlers (`:doorman/my-actions`, `:groundwork/browser-error`) have none.
