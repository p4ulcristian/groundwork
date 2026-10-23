# Daily caps are counted by the composer, in Postgres, after the permission check

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P8: 53 handlers cap a person per day; `:feedback/send-email` has three limits). Not built yet.

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
