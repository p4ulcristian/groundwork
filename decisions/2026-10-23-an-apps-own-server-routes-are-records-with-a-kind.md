# An app's own server routes are records with a kind; the composer does their checks

> The user's decision, 2026-10-23, for Iron Rainbow's move to v1 (its 20 route lists: Stripe and Meta webhooks, the WorkOS login, `/mcp`, storage, the catalog API). Adds to [Routing is Groundwork's, and a route is a record](2026-10-14-routing-is-groundworks-and-a-route-is-a-record.md), which is about the browser's pages. Not built yet.

**Decision:** an app names its own server routes as records in the app map, each with a kind, and the composer serves them beside its own, through the same steps:

```clojure
:routes {:stripe/webhook {:address "/stripe-webhook" :method :post :kind :server-to-server
                          :rate-limit {:max 30 :per-ms 60000} :handler stripe/process}
         :flex/authorize {:address "/flex/authorize" :method :get :kind :browser
                          :rate-limit nil :handler flex-authorize}}
;; a handler: (fn [request {:keys [asker run-as-asker! run-as-system! sign-in! sign-out!]}])
```

- **`:browser`**: the Origin check and doorman's session step run first; the handler gets the asker, and can sign in or out (doorman's `sign-in!` on "the app's own route").
- **`:server-to-server`**: no session; the handler proves the caller itself (a signature, a token).
- Every key is required; `:rate-limit` nil where none is meant.

**Why:** the checks a route needs (Origin, rate limit, who is asking) are done once, by the composer, and can't be left out of one route. Iron Rainbow's `:access` declarations map onto it (`:session` → `:browser`, `:secret`/`:bearer` → `:server-to-server`), and it builds the server-to-server pages Groundwork decided on 2026-10-12.

**The price:** a bigger composer; doorman needs a public way to run a transaction as the asker; a webhook needs its body unparsed.

**Instead of:**
- a routes function the app returns (`:make-routes`): each route must remember its own checks
- the app's router in front of the composer's: two routers, the language step skipped, clashes unseen
