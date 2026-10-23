# A token call is answered as its person, from the app's own route

> **Changed in part** by [A token call arrives where a browser's does; the composer names no person](2026-10-23-a-token-call-arrives-where-a-browsers-does.md) (2026-10-23, built on tidy-up, not released): doorman checks the token (its own, `Authorization: Bearer`), not the app; `answer-as` is `(answer-as message request)`, no asker and no `:rate-limit-key`; token calls arrive on the messages' route and `:browser` routes, and `:server-to-server` routes have no `answer-as`.

> The user's decision, 2026-10-23, for Iron Rainbow's move to v1 (`/mcp` with WorkOS AuthKit tokens, the Labs catalog API's tokens). Builds on [An app's own server routes are records with a kind](2026-10-23-an-apps-own-server-routes-are-records-with-a-kind.md). Built the same day: `answer-as` in a route's tools (`groundwork.server.main.answer-as`), bridge v0.7.6's `make-message-limiter`.

**Decision:** the running app can answer a message as a given asker, through every check the browser's message goes through (doorman's access and gates, the shapes, a rate limit):

```clojure
;; an app's :server-to-server route
(if-let [person-id (person-for-token pool (bearer-token request))]
  (answer-as {:parts/update-part params}                             ; the route's tools' answer-as; JSON data
             {:person-id person-id :visitor-id nil :session-flags #{}}
             {:rate-limit-key (str "token:" person-id)})
  {:status 401})
```

The app checks the token (a WorkOS JWT, a Labs token) and names the person; groundwork answers as that person. It is today's REPL tool `ask-as-person` (`groundwork.server.main.tools`), made public, rate-limited, on handlers built once.

**Why:** a token call gets exactly the browser's checks, so what a person may do is the same however they ask. Checking a token stays the app's, as doorman ships no logins.

**The price:** each token kind is an app route; it needs the app's own routes built first.

**Instead of:**
- doorman reading `Authorization: Bearer` in its session step: `/mcp` and the catalog batch aren't messages, so they need routes anyway, and a token would be good for every handler
- a system actor (`run-as-system!`): no person, so no owner in the catalog's history and no Labs check; MCP must act as the shop's user
