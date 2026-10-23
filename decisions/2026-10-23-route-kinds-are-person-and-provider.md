# An app's own route is of kind :person or :provider

> The user's decision, 2026-10-23, from the tidy-up. Changes in part [An app's own server routes are records with a kind](2026-10-23-an-apps-own-server-routes-are-records-with-a-kind.md) (its `:browser` and `:server-to-server`) and [A token call arrives where a browser's does](2026-10-23-a-token-call-arrives-where-a-browsers-does.md), which the user agreed to the same day. Built on tidy-up, not released.

**Decision:** a route's `:kind` says who calls it:

```clojure
:routes {:flex/authorize {:address "/flex/authorize" :method :get  :kind :person   :rate-limit nil :handler flex-authorize}
         :stripe/webhook {:address "/stripe-webhook" :method :post :kind :provider :rate-limit {:max 30 :per-ms 60000} :handler stripe/process}}
```

- **`:person`** (was `:browser`): a person calls it, from their browser (the session's cookie) or a script (a token doorman made for them). doorman's step finds who asks.
- **`:provider`** (was `:server-to-server`): another company's server calls it (a payment provider's webhook); no cookie or token is read, the handler proves the caller (a signature) and runs as a named system actor.

There is no `:token` kind.

**Why:** `:browser` stopped being true when a person's script could call the same route with a token. A kind names who calls; how a person proves who they are (cookie or token) is doorman's, not the route's. Accepting both does no harm: a cookie call keeps the Origin check, a token does only what its person may, and making a token refuses a token's call.

**The price:** apps rename `:browser` to `:person` and `:server-to-server` to `:provider` in their `:routes`. A route that must refuse one of the two proofs can't say so; that is decided when a real route needs it.

**Instead of:**
- **keeping `:browser` / `:server-to-server`**: `:browser` would also mean a script
- **a third kind, `:token`**: splits one caller, a person, by how they prove it, and a message by token would still arrive on the messages' route
