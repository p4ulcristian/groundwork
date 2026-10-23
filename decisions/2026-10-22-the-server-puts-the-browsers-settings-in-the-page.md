# The server puts the browser's settings in the page

> **Changed in part** by [main makes one wire and passes it to every library](2026-10-22-main-makes-one-wire-and-passes-it-to-every-library.md) (2026-10-22): the page also carries the browser's wire settings (`:browser-wire`, under `"wire"`), and the browser reads it with `JSON.parse`.

> **Changed in part** by [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22): the page also carries `:sse-reconnect-after-ms` and `:ws-reopen-after-ms`; the shell's viewport, referrer policy and frame options are `:page` settings.

> Replaces the two copies of `contract.cljc` from [One tree](2026-10-22-one-tree-libraries-edn-checks-the-edges.md) and [Named messages over three addresses](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-named-messages-over-three-addresses.md) (the addresses and the pages were said on both sides, kept equal by a test).

**Decision:** the page shell (groundwork.server.main) carries what the browser needs to start, as JSON in a script tag the browser never runs:

```html
<script type="application/json" id="groundwork-settings">
  {"version":"2026-10-22.1",
   "addresses":{"http":"/groundwork/http","sse":"/groundwork/sse","ws":"/groundwork/ws"},
   "pages":{"orders/page":{"address":"/orders/:order-id","title":"Order"}}}
</script>
```

- The pages and the three addresses are said once, on the server; groundwork.browser.main reads them. The `contract.cljc` copies and their test are gone; Groundwork has no `.cljc` left.
- The version: every sse line hears `{:groundwork/version "…"}` first. A page older than the server reloads, once per version (a rolling deploy with two versions can't reload it back and forth).
- The handlers' shapes stay in the app's own `.cljc`: they are code (malli), and a large app's shapes in every page would be too much to send.
- The shell is never cached (`cache-control: no-store`), so the next load always gets the new version's scripts.

**Why:** what both sides must agree on is said in one place, and the browser can't disagree with the server that sent it. A deploy reaches open tabs without anyone reloading by hand.

**The price:** the page's HTML grows with the pages map. A page can't start without the shell (opening the built JS on its own fails, with an error that says so).

**Instead of:**
- a `.cljc` contract on both sides, kept equal by a test: two copies, and the browser's copy could be older than the server it talks to
- a request for the settings when the page starts: one more round trip before anything shows
- a CDN-served page: the version and the settings would need their own way in
