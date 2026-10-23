# A token call arrives where a browser's does; the composer names no person

> **Agreed by the user** 2026-10-23, with the kinds renamed by [An app's own route is of kind :person or :provider](2026-10-23-route-kinds-are-person-and-provider.md): read `:browser` as `:person` and `:server-to-server` as `:provider` below.

> Chosen while building, 2026-10-23, **for the user's review**: doorman's [doorman owns a person's tokens, and only sign-in names a person](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md) left open "which route kind token calls arrive on". Changes in part [A token call is answered as its person, from the app's own route](2026-10-23-a-token-call-is-answered-as-its-person-from-the-apps-route.md), [An app's own server routes are records with a kind](2026-10-23-an-apps-own-server-routes-are-records-with-a-kind.md), [The REPL starts last and stops first; house brings its tools](2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md) and [A page says its own head and body through the app's render-page](2026-10-23-a-page-says-its-own-head-and-body-through-the-apps-render-page.md). Built on tidy-up, not released (doorman and bridge on their tidy-up commits).

**Decision:** no new route kind. doorman's session step reads a token (`Authorization: Bearer`) as it reads the cookie, so a token call goes where a browser's does:

- **a message**, on the messages' own route (`POST /groundwork/http`), with the token and no Origin:

  ```
  POST /groundwork/http
  Authorization: Bearer shop_q3V…
  {"orders/of-shop": {"shop/id": "…"}}
  ```

- **another protocol** (MCP's JSON-RPC, a batch API), on a `:browser` route, which turns the call into a message and hands it to `answer-as` with its own request:

  ```clojure
  :mcp/call {:address "/mcp" :method :post :kind :browser :rate-limit {:max 60 :per-ms 60000}
             :handler (fn [request {:keys [answer-as]}]
                        {:status 200 :body (json-text (answer-as (turn-rpc-into-message request) request))})}
  ```

`:browser` now says "a person's": their browser's cookie or their script's token. `:server-to-server` reads neither and has no asker, so it loses `answer-as`: a provider's webhook runs as a named system actor (`run-as-system!`).

No composer code names a person:

- **`answer-as` is `(fn [message request])`**, on the server's own ready handlers (doorman's real session step), its rate limits counted per asker by the server's key (a token's hash, else the cookie's, else the address). Its `:rate-limit-key` option is gone.
- **`run-as-asker!`**, in a route's and a page's tools, is doorman's `run-as-asker-of-request!` on the route's request.
- **The REPL's `ask-as-person`**, the one place that names a person, makes the handlers ready with `doorman.repl`'s step (outside `doorman.api`) in the session step's place, and answers by `answer-as`'s own `answer-message`, with no request (bridge's `answer-message` takes nil for a message that came with none). The two copies of a stand-in step (`answer_as.clj` and `tools.clj`) and the made-up browser request (`{:request-method :post :uri "/" :headers {"origin" …}}`) are gone. `libraries_test` lets only `groundwork.server.main.tools` require `doorman.repl`.

**Also built here**, doorman's [a handler says where it acts](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md) taken: every handler of the test app and the sample writes `:record` or `:scope` (the sample's `:orders/of-shop` is `:scope [:shop :shop/id]`, so another's shop answers "Not found." instead of an empty list, which the end-to-end test now expects), every sentence `:only`. That "Not found." showed bridge's browser half holding a refusal to the answer shape; bridge now passes the server's error answers as they are ([its decision](https://github.com/p4ulcristian/bridge/blob/main/decisions/2026-10-23-the-browser-passes-the-servers-error-answers-past-the-shape.md)).

**Why:** the token is checked where the cookie is, by doorman, so what a person may do is the same however they ask, and a route can't forget to check it. A message needs no route of its own; only a call in another protocol needs one, and it is already a person's call.

**The price:** every `:browser` route and the messages' route take tokens; an app that wants a route for browsers only can't say so yet. An app's own bearer tokens of another kind (WorkOS's OAuth tokens on `/mcp`) on a `:browser` route are read by doorman as doorman tokens, so nobody: by "the session is the only truth" the app checks one once and doorman issues its own, which isn't built.

**Instead of:**
- **a `:token` route kind, bearer only**: a third kind to know, and a message by token would still need the messages' route to take tokens, or a route per message
- **`answer-as` keeping an asker argument, the app checking its tokens**: any server code could name anyone (doorman's decision)
- **the REPL faking a signed-in request (a real session made, its cookie sent)**: a session row per REPL call, and no way to ask as an existing visitor
