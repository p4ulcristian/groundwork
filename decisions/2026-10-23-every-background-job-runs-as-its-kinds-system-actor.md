# Every background job runs as its kind's system actor, through doorman's run-as-system!

> The composer's part of backroom's [A job kind names its system actor, and its one transaction runs as it](https://github.com/p4ulcristian/backroom/blob/tidy-up/decisions/2026-10-23-a-job-kind-names-its-system-actor.md) (the user's decision, 2026-10-23). Changes in part [postbox's webhook functions run as doorman's system actor :postbox](2026-10-23-postboxs-webhook-functions-run-as-doormans-system-actor-postbox.md) and [Every write runs once, by its one-time key](2026-10-23-every-write-runs-once-by-its-one-time-key.md). Built on tidy-up, not released.

**Decision:** `start-app!` hands backroom's workers doorman's `run-as-system!` as `:run-in-transaction-as`, so every job's one transaction (its work and its done-mark) runs as its kind's `:system-actor`: the app's kinds, groundwork's own and postbox's.

```clojure
;; an app's kind
{:name :orders/send-confirmation-email :system-actor :order-emails :params-shape … :run … :timeout-ms 30000 :attempts 5}
;; and the sentences that give the actor what it reaches, on each gated table
{:allow :orders/see :only #{:read} :to [:system :order-emails]}
```

**Why:** a job reaches exactly what its actor's sentences give it, and doorman's `find-system-leaks` can prove it.

## Settled while building

Choices made while building, not asked:

1. **`run-as-system!` with `{:read-only? false}`** for every kind: a job writes at least its done-mark.
2. **groundwork's forgetting of old one-time keys runs as `:groundwork-once-keys`**, an actor of its own; it deletes from `groundwork.run_once_keys`, a table without sentences, so no sentence names it.
3. **postbox's sending runs as `:postbox`,** the actor its webhooks run as, handed to `make-postbox` as `:system-actor` (postbox's tidy-up option): it reaches `postbox.emails`, a table without sentences.
4. **The actors are named in code, not settings:** they are names the app's sentences use.
