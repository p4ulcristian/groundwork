# The composer takes the browser builder's changes; the start tells the app its sse line's status

> The user's ask, 2026-10-23, after bridge's, parlor's and browser-build's tidy-up changes ([bridge's](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/README.md), [parlor's](https://github.com/p4ulcristian/parlor/blob/tidy-up/decisions/README.md), [browser-build's](https://github.com/p4ulcristian/browser-build/blob/tidy-up/decisions/README.md) decisions). Changes in part [The browser start starts parlor](2026-10-23-the-browser-start-starts-parlor.md) and [Every write runs once, by its one-time key](2026-10-23-every-write-runs-once-by-its-one-time-key.md). Built on tidy-up, not released.

**Decision:**

- **A write's send takes its options**, `:send-write!` is `(fn [message {:keys [once-key keep-alive?]}])`: the key the caller made (`parlor/make-once-key`, kept with its draft) in the `idempotency-key` header, `:keep-alive?` as bridge's `send-message!` takes it; a read's send writes `:keep-alive? false`.
- **The start takes `:on-line-change`, required**: an event dispatched with `{:line :open}` when the sse line first opens, `{:line :broken}` once per break (bridge's `:on-break`), `{:line :back}` when it opens again; nil for none. An offline banner reads it.
- **What an app writes out grows**: every module its `:start` (nil for none); a release build `:silenced-warnings`, `:css` (nil: no CSS step, the bundles as their files are written; or `{:targets "…"}`, the lightningcss program being browser-build's pin) and `:lightningcss-download-timeout-ms`; the `:browser-build {:watch …}` settings `:preloads` too; every `:parlor/hold` its `:ask-with`; every `:parlor/send` its options; every `:parlor/go-to` its way, `:push` or `:replace`.

**Why:** every behaviour value written out, as each library now takes it; the line's status is known only where the start opens the line.

**Instead of:**
- **the composer making a key per send**: a second Save of a draft would be a new write
- **the app opening its own sse line for the status**: two lines, one of them not doorbell's
