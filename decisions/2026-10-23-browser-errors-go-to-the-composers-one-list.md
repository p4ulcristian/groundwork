# Browser errors go to the composer's one list, which the app's monitor reads

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, G18: Eye on Rainbow reads IR's own `/internal/errors`). Not built yet.

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
