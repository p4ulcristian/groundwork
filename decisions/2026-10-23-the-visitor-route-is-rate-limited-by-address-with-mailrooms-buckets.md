# The visitor route is rate-limited by the asker's address, with mailroom's buckets

> 2026-10-23: the user asked for a rate limit by asker (the client's address, through mailroom's `find-client-address`), from required settings, answering 429; reusing mailroom's limiter was decided while building it. Changes in part [A visitor's session starts on a POST of house's, never on a page load](2026-10-23-a-visitors-session-starts-on-a-post-of-houses.md) ("the route has no rate limit yet").

**Decision:** the `:visitors` settings gain `:rate-limit`, required, `{:max 10 :per-ms 60000}`: how many POSTs at `:path` one asker may send. The asker is their address, mailroom's `find-client-address` with the `:server` settings' `:client-address-header` (the proxy's header in production, the request's own address otherwise); a visitor POSTs before they have a session, so no cookie tells them apart. Over it the route answers `429` `"Too many requests."` and makes no session and no cookie. The limit is counted before the Origin check, so another site's POSTs count too. It is mailroom's limiter, the token buckets a handler's `:rate-limit` uses, taken through mailroom's api (`make-rate-limited-ring-handler`, mailroom v0.7.4), its buckets dropped above the `:server` settings' `:rate-limit-buckets-before-cleanup`.

**Why:** a script on the app's Origin could make sessions, and rows, as fast as it could POST. mailroom's limiter already counts askers with buckets in memory; a plain route needed only a way in, which mailroom's api now has (its [A plain route is rate-limited by the same buckets](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-23-a-plain-route-is-rate-limited-by-the-same-buckets.md)).

**The price:** the buckets are each server's own memory: behind several servers each counts its own. Many visitors behind one address (an office) share one bucket.

**Instead of:**
- a small limiter in groundwork: a copy of mailroom's
- the session's key: a visitor has none yet
- a limit at the proxy only: the settings wouldn't say it, and development would have none
