# Main serves the kept errors to a monitor, and logs the browser's

> Builds on logbook's [An error is a log line, kept for pulling](https://github.com/p4ulcristian/logbook/blob/main/decisions/2026-10-22-an-error-is-a-log-line-kept-for-pulling.md), which leaves the route's path and token to the app's composition, and on [main makes one logbook](../../2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md).

**Decision:** a required `:errors` part of the settings:

```clojure
:errors {:path               "/house/errors"
         :token              "…"                        ; a secret: from the environment, never in the code
         :browser-rate-limit {:max 10 :per-ms 60000}}   ; browser errors per asker
```

- **The errors route**: a GET at `:path`, beside the health checks, outside sessions, the Origin check and rate limits. It answers only a request whose `Authorization` header is `Bearer <token>`; both are hashed (SHA-256) and the hashes compared with `MessageDigest/isEqual`, so the time taken says nothing about the token; else 401, `www-authenticate: Bearer`. `?since=N` (a whole number from 0, at most 18 digits) answers 200, the JSON array of logbook's `list-errors-since`: each kept `:error` line as written, its `"seq"` first, oldest first, `[]` for none; a missing or bad `since` is 400. Never cached.
- **The browser's errors**: house's browser start adds, once for the page, a listener for `error` (an uncaught error) and `unhandledrejection` (a promise rejected unhandled), and sends each as a message on http, `{:house/browser-error {:message … :stack … :url …}}` (the stack nil when there is none; a rejection with a value that isn't an Error is its text). A failure to send is dropped: it couldn't be sent either. On the server it is a handler of house's own, added after doorman's and doorbell's: doorman's find-session step (the Origin checked; the person added to the message's log), then `(log :error :browser/uncaught {:browser/message … :browser/stack … :browser/url …})`, answered `true`. Being `:error`, it is kept with the server's errors, with the request id and the person. Its `:rate-limit` is `:browser-rate-limit`, per asker (a session, else an address): a page failing in a loop is answered "Too many requests." and writes nothing. An app's own handler named `:house/browser-error` is refused at the start.

**Why:** the monitor pulls (no outbound credential in the app); the route is a plain GET a monitor can ask, like the health checks, and the token is its only gate. The browser's errors take the path every message takes, so they arrive in the same list with the same context, and the rate limit the server already has keeps a loop from filling the log. The browser's handler is outside doorman's access check because anyone, signed in or not, can hit an error; the session step still runs, so another site can't post errors and a signed-in person's are named.

**The price:** the settings grow by one part (3 keys, the token a secret the deploy provides). The handler answers `true`, an answer the browser ignores. Errors between restarts not pulled are lost from the list (logbook's price).

**Instead of:**
- the token in a query parameter: it would land in proxy and access logs
- comparing the token text directly: the time to the first differing character tells an attacker how much is right
- a browser error as a separate POST route: a second way in, without the session step, the rate limit or the request id
- the browser's errors through doorman's access check: a handler needs an action someone holds, and a visitor's error would be refused
- `since` optional, 0 when left out: a value filled in; a monitor says what it has
