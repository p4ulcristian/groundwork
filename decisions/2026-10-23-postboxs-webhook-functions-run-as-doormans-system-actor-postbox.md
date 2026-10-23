# postbox's webhook functions run as doorman's system actor :postbox

> 2026-10-23: the user asked for a deliberate, narrow system path, allowed exactly what is declared in the sentences, covered by the leak test, without BYPASSRLS or the owner role; this shape was decided while building it. Changes in part [main makes one attic and one postbox, and serves the upload route and the provider's webhooks](2026-10-23-main-makes-one-attic-and-one-postbox.md): the app's `:on-delivery-event` and `:on-inbound-email` ran on the app's role with nobody asking, so a bounce couldn't mark a shop's row behind the gates.

**Decision:** groundwork hands postbox's webhooks `:run-in-transaction` as `#(doorman/run-as-system! pool :postbox {:read-only? false} %)`: every webhook function runs in a transaction in which doorman's system actor `:postbox` asks. The app's sentences say what it may do, by name:

```clojure
{:table  :shop-emails
 :access [{:allow :shop-emails/see          :to [:member :shop-email/shop-id]}
          {:allow :shop-emails/mark-bounced :to [:system :postbox]}]}   ; an action of the :shop kind, #{:read :update}
```

The gates allow it exactly that, the open rows and the tables without sentences; nothing else (the same function reads no order). doorman's `find-system-leaks` proves it in the app's tests. The name `:postbox` is groundwork's, like `:doorman/my-actions`. It runs on the app's own role, which owns nothing: no role passes the gates for it (doorman's [A named system actor is allowed what its sentences give it](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-23-a-named-system-actor-is-allowed-what-its-sentences-give-it.md), postbox's [The app makes the transaction a webhook's function runs in](https://github.com/p4ulcristian/postbox/blob/main/decisions/2026-10-23-the-app-makes-the-webhooks-transaction.md)).

**Why:** a bounce belongs to no person; its function must reach the row it is about and no other. Declared in the sentences, it is read by the same conditions as everyone's, and the leak test covers it.

**Instead of:**
- BYPASSRLS, or the tables' owner, for the webhooks: every table opens to a function that needs one row
- the app opening its own transaction in each function: every function the same lines, and the actor chosen differently in each
- a setting naming the actor: the name is a contract between groundwork and the app's sentences, not a value that changes behaviour per environment
