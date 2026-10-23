# Every write runs once, by its one-time key

> **Changed in part** by [The composer takes named refusals](2026-10-23-the-composer-takes-named-refusals.md) (2026-10-23, built on tidy-up, not released): the run-once refusals are named reasons, `:groundwork/once-key-not-a-uuid` and `:groundwork/once-key-of-someone-else`, and a write whose answer doesn't fit answers `:bridge/failed`.

> **Changed in part** by [Every background job runs as its kind's system actor](2026-10-23-every-background-job-runs-as-its-kinds-system-actor.md) (2026-10-23, built on tidy-up, not released): the job that forgets old keys runs as `:groundwork-once-keys`.

> The user's decision, 2026-10-23: as Groundwork v0.5.0 did ([What every endpoint declares](2026-10-12-what-every-endpoint-declares.md): "run-once is automatic for every write"). Takes mailroom v0.5.0 (a message may carry more headers), doorman v0.5.0 (`does-action-only-read?`) and parlor v0.4.0 (a write carries a one-time key, kept across its retries). Changes in part [The browser start starts parlor](2026-10-23-the-browser-start-starts-parlor.md): parlor's start gets `:send-write!` and `:retry-writes` in place of `:send-message!`.

**Decision:**

- **The key.** parlor's `:parlor/send` makes a random UUID for each send and keeps it across that send's retries; house's browser start sends it in the request's `idempotency-key` header (mailroom's `:headers`).
- **Which handlers are keyed.** Every app handler whose `:action` may write: doorman's `does-action-only-read?` says no, the same rule that gives a reading action a read-only transaction. No handler flag. A handler that only reads is never keyed, whatever its request carries.
- **Where.** The handler's steps are wrapped in one step, `:house/run-once` (`house.server.main.run-once`), after doorman's check, inside the handler's own transaction. It claims the key for the message's name in `house.run_once_keys`, house's own table in its own folder (key and message name the primary key; who claimed it; the answer as JSON text by the app's wire; when). Claimed now: the steps run and their answer is kept with the key, in the same transaction; a rollback takes the claim with it.
- **A repeat** (same key, same message name, same person) gets the first answer back and saves nothing. The same key from someone else is refused, `{:error "This one-time key was used by someone else."}`, and nothing runs.
- **A repeat while the first still runs waits for it**, then answers the first answer; if the first rolled back, the repeat runs. Postgres does the waiting: the claim is an insert on the key, which waits on the first transaction's uncommitted one. The wait is bounded by the pool's own time limits (`:statement-timeout-ms`, `:lock-timeout-ms`).
- **Keys are forgotten after `:keep-keys-ms`**: a claim treats an older key as forgotten and claims it anew, so the rule holds to the millisecond whenever the clean-up runs. house's own background job, `:house/forget-old-once-keys`, deletes them on a backroom schedule, its cron line, time limit and attempts the `:forget-keys` settings.
- **A write's answer must fit its `:answer-shape`, or nothing is saved**: the step checks it inside the transaction and throws, so the asker gets "Something went wrong." and the log the details, as v0.5.0 did. Keyed or not.
- **A write without a key runs as it is**, every time: a message on a ws line (no headers), a test's `answer-message`, a caller that isn't parlor. A key that isn't a UUID's text is refused, `{:error "The one-time key must be a UUID."}`.
- **The settings**, every key required:

  ```clojure
  :run-once {:keep-keys-ms 86400000                                      ; a day
             :forget-keys  {:cron "*/15 * * * *" :timeout-ms 60000 :attempts 3}   ; house's background job
             :retry-writes {:times 2 :wait-ms 1000}}                     ; the browser's: a write that got no answer, again
  ```

  `:retry-writes` goes to the browser in the page, to parlor's start; the retries must end before the key is forgotten (`:times` × `:wait-ms` below `:keep-keys-ms`), or the settings are refused.

**What stays fixed, and why:** the header's name, `idempotency-key`: the IETF's Idempotency-Key header, the name a proxy, a log or a person reading a request knows; both ends are house's. The key is a UUID: parlor makes random ones, and a row's size is bounded. The texts of the two refusals: what house answers, read by its tests.

**Why:** a write that got no answer (the network, a proxy timing out, a deploy) may have been saved or not; with the same key the server tells the two apart, and a placed order is placed once. Waiting, rather than answering "in progress": the retry gets the real answer, and the browser has nothing to poll or to ask again. Claiming in the write's own transaction: the key is kept exactly when the write is. A header rather than a reserved name in the message: it is about the request, not its data; mailroom's message format stays the names and their data; and Ring hands it to the steps as it hands them the cookie. A cron line rather than a clean-up in each write or at the start: no write pays for it, and a server that runs for weeks doesn't keep weeks of keys.

**The price:** one row per keyed write, kept `:keep-keys-ms`. A keyed write's notes name `house/run_once_keys` too (its claim is read inside the steps doorbell notes); a repeat's notes name only that table, since its steps didn't run: the first run's changes reached the screens by doorbell's ring, and a line that broke asks every watched question again when it opens. A repeat doesn't check that its params match the first's. Writes over ws aren't keyed.

**Instead of:**
- the key as a reserved name beside the message's names (`:mailroom/once`): mailroom's protocol carrying house's idea, on both sides
- a handler flag (`:writes? true`): a second answer beside the action's, which could disagree with the transaction the handler runs in
- answering "in progress" to a repeat: the browser would have to ask again, with no way to get the first answer
- the clean-up at the start (v0.5.0) or in each write: a long-running server keeps every key, or every write pays for the clean-up
- refusing a write without a key: no ws write could run, nor a test's `answer-message`
- one day fixed (v0.5.0's `interval '1 day'`): a setting, like every value that sets behaviour
