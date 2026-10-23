# A visitor's session starts on a POST of house's, never on a page load

> The user's decision, 2026-10-23 (doorman v0.7.0's visitor sessions). Takes doorman's [Visitors get sessions of their own, and their rows move to the person at sign-in](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-23-visitors-get-sessions-and-their-rows-move-at-sign-in.md), which leaves "a route (or a page load)" to the app.

**Decision:**

- **A POST at the `:visitors` settings' `:path`** (`house.server.main.visitors`), every key required: `{:path "/house/visitor"}`. It calls doorman's `start-visitor-session!` and answers `200 {"visitor-id": "…"}` with its `Set-Cookie`; a request that has a live session already gets its visitor id (nil for a person's) and no cookie. Outside the messages' route, never cached.
- **From the app's own pages only**: a request whose `Origin` isn't the `:sessions` settings' `:allowed-origin` is answered 403 and makes nothing.
- **The browser calls it** the first time the app keeps something for a visitor (a cart): `house.browser.main.api/start-visitor-session!`, a promise of the visitor's id. The page carries the path, `:visitor-path`.
- **doorman v0.7's other changes** are taken with it: `:visitor-session-days` in the `:sessions` settings; `sign-in!` takes the request and `{:settings :tables :session-flags}`; `install-access-functions!` the tables and scope kinds; a scope kind writes out `:table :parent :single? :actions :roles :denies`; `add-scope!` takes a parent. `doorman.sessions` gains its visitor column through cellar's migrate.

**Why:** a page load would make a session, and a row, for every crawler and every person who only looks; the app knows when it first keeps something. A message's answer sets no cookie (mailroom answers JSON), so it is a Ring route of house's, as health and errors are.

**The price:** one round trip before the first kept thing. The route has no rate limit yet (mailroom's limiter counts messages, not routes): a script can make sessions as fast as it can POST from the app's Origin. Ended visitors' rows are the app's to clean up.

**Instead of:**
- a session on every page load: a row per crawler, cookies before consent
- a message: its answer can't set the cookie
- the app writing its own route: every app the same twelve lines, and the Origin check forgotten in one
