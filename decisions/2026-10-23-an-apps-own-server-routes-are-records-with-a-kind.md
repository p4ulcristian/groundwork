# An app's own server routes are records with a kind; the composer does their checks

> **Added to** by [A token call is answered as its person, from the app's own route](2026-10-23-a-token-call-is-answered-as-its-person-from-the-apps-route.md) (2026-10-23): a `:browser` route's tools gain `answer-as`, written into the handler's tools below.

> **Changed in part** by [An app's own route is of kind :person or :provider](2026-10-23-route-kinds-are-person-and-provider.md) (2026-10-23, built on tidy-up): `:browser` is `:person`, `:server-to-server` is `:provider`.

> **Changed in part** by [A token call arrives where a browser's does; the composer names no person](2026-10-23-a-token-call-arrives-where-a-browsers-does.md) (2026-10-23, built on tidy-up, not released): a `:browser` route is a person's, by their cookie or their token (no Origin check for a token, both refused); its `run-as-asker!` is doorman's `run-as-asker-of-request!`, its `answer-as` takes the request; `:server-to-server` has no `answer-as`.

> The user's decision, 2026-10-23, for Iron Rainbow's move to v1 (its 20 route lists: Stripe and Meta webhooks, the WorkOS login, `/mcp`, storage, the catalog API). Adds to [Routing is Groundwork's, and a route is a record](2026-10-14-routing-is-groundworks-and-a-route-is-a-record.md), which is about the browser's pages. Built the same day (`groundwork.server.main.app-routes`, doorman v0.7.7's `run-as-asker!`).

**Decision:** an app names its own server routes as records in the app map, each with a kind, and the composer serves them beside its own, through the same steps:

```clojure
:routes {:stripe/webhook {:address "/stripe-webhook" :method :post :kind :server-to-server
                          :rate-limit {:max 30 :per-ms 60000} :handler stripe/process}
         :flex/authorize {:address "/flex/authorize" :method :get :kind :browser
                          :rate-limit nil :handler flex-authorize}}
;; a handler: (fn [request {:keys [asker run-as-asker! run-as-system! answer-as sign-in! sign-out!]}])
```

- **`:browser`**: the Origin check and doorman's session step run first; the handler gets the asker, and can sign in or out (doorman's `sign-in!` on "the app's own route").
- **`:server-to-server`**: no session; the handler proves the caller itself (a signature, a token).
- Every key is required; `:rate-limit` nil where none is meant.

**Why:** the checks a route needs (Origin, rate limit, who is asking) are done once, by the composer, and can't be left out of one route. Iron Rainbow's `:access` declarations map onto it (`:session` → `:browser`, `:secret`/`:bearer` → `:server-to-server`), and it builds the server-to-server pages Groundwork decided on 2026-10-12.

**The price:** a bigger composer; doorman needs a public way to run a transaction as the asker; a webhook needs its body unparsed.

**Instead of:**
- a routes function the app returns (`:make-routes`): each route must remember its own checks
- the app's router in front of the composer's: two routers, the language step skipped, clashes unseen
