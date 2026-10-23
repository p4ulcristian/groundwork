# Browser errors go to the composer's one list, which the app's monitor reads

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, G18: Eye on Rainbow reads IR's own `/internal/errors`). Built on tidy-up, not released.

**Decision:** an app keeps no error list of its own. Server and browser errors are one numbered list, the composer's (`GET /groundwork/errors?since=N`, its token), and the app's monitor reads it. A browser report gains, every key written out:

```clojure
{:kind    :render            ; :render (an error boundary caught it) or :uncaught
 :version "a1b2c3d"          ; the built bundle's version
 :message … :stack … :url …}
```

`report-browser-error!` is public in the browser, for an app's error boundary; the composer's listener reports uncaught errors as before. Iron Rainbow's own endpoint and reporter go, and Eye on Rainbow's reader moves to the composer's list once.

**Why:** one list, no report sent twice, one place to read every error.

**The price:** the report's shape grows; Eye on Rainbow's reader changes.

**Instead of:**
- **IR keeping its own endpoint and reporter**: the composer's listener sends every error a second time
- **the monitor reading both lists**: two lists, and the composer's would lack the kind and the version

**Settled while building:**

1. **The line's event is `:browser/error`**, in place of `:browser/uncaught`, with `:browser/kind` and `:browser/version` beside the message, stack and address: a `:render` error isn't uncaught. A monitor that looked for `browser/uncaught` looks for `browser/error`.
2. **`:kind` is a keyword on both sides, `[:enum :render :uncaught]`**, and both keys are required in the message's shape, server and browser.
3. **`:version` is the page's version** (the `:version` setting the page shell carries), which names the bundle the page loaded.
4. **`(report-browser-error! app {:kind :render :error e})`**, `app` what `start-app!` returned (it carries the sender, under `:groundwork.browser.main/send-error!`), the report checked by malli (`:groundwork.browser.main/bad-report`); `e` an Error gives its message and stack, anything else its text. It returns nil; a failure sending is dropped, as the listener's is. It is sent by the same message, so the server's `:browser-rate-limit` holds for it too.
5. **Iron Rainbow's own endpoint, reporter and Eye on Rainbow's reader are not changed here**: they are Iron Rainbow's and Eye on Rainbow's to move.
