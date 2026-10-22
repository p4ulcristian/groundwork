# Every value that sets behaviour is a setting, in main too

> **Changed in part** by [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md) (2026-10-22): sync's message shapes are doorbell's `message-shapes-by-name`, and its messages `:doorbell/changed` and `:doorbell/missed`.

> Changes in part [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md), [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md) and [One start, and a migrate for the deploy](server/main/2026-10-22-one-start-and-a-migrate-for-the-deploy.md): Groundwork takes mailroom v0.1.3 and doorman v0.1.1, and main's own fixed values become `:page` settings.

**Decision:** Groundwork follows mailroom's [Every value that sets behaviour is a setting; no hidden defaults](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) and [An sse line reopens after an HTTP error too](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-sse-reopens-after-an-http-error.md), and holds its own main to the same rule.

- **mailroom v0.1.3 and doorman v0.1.1.** The `:server` settings, mailroom's shape, gain `:proxy-protocol`, `:server-header`, `:max-body-bytes`, `:max-header-line-bytes`, `:max-ws-message-bytes`, `:sse-reconnect-after-ms` and `:stop-wait-after-cut-ms`, every key required. Every handler writes out `:rate-limit` and `:run-steps-in` beside its shapes and steps (`:run-steps-in` nil: doorman's transaction runs the steps); every page its `:title`. The version goes out on an sse line with its id written out, nil.
- **The page carries `:sse-reconnect-after-ms`** to the browser beside `:ws-reopen-after-ms`; the browser's sse line opens with it (`:reconnect-after-ms`), so a line the server answered with an HTTP error, a deploy's 502, opens again after it, and every watched question is asked again.
- **The browser has both shapes for every name it hears.** `groundwork.browser.main` adds Groundwork's own to the app's: `:groundwork/version` (a text) and sync's two messages (`sync/sse-shapes-by-name`). A name without shapes would arrive as an answer that doesn't fit.
- **main's own values are `:page` settings**, every key required:

  ```clojure
  :page {:title                   "Shops"
         :language                "en"
         :viewport                "width=device-width, initial-scale=1"   ; the viewport meta
         :content-security-policy :none                                   ; or the header's text
         :referrer-policy         "same-origin"                            ; the header
         :frame-options           "DENY"                                   ; "SAMEORIGIN", or :none: other sites may frame it
         :files                   {:folder                     "target/public"
                                   :hashed-name-min-hex-digits 8           ; main.3F9A0C1D.js is hashed
                                   :hashed-file-cache-seconds  31536000}}  ; and kept this long
  ```

  The viewport, the referrer policy and the frame options were fixed in the shell (`width=device-width, initial-scale=1`, `same-origin`, `DENY`); `:files` replaces `:files-folder` and is mailroom's `:files`, the folder also where showroom writes the watched build and where the release build's manifests are read.

**What stays fixed, and why:**

- **The shell is never cached** (`cache-control: no-store`): it names this version's scripts; a kept shell loads old code after a deploy.
- **`x-content-type-options: nosniff`**: every file goes out with its type; sniffing only helps a file sent with the wrong one, and lets an upload run as a script.
- **UTF-8, deferred scripts, `#app`, the `groundwork-settings` script tag, the `groundwork-reloaded-for` key in sessionStorage**: the shell's protocol with the browser's start, read by both ends of Groundwork.
- **The watched build's addresses** (`/js/main.js`, `/css/main.css`) and the manifests' places (`js/manifest.edn`, `css/manifest.edn`): what showroom writes.
- **A page older than the server reloads, once per version**: [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md).
- **Askers for rate limits are told apart by session, else by address**: the design of main's rate-limit key, not a number.

**Why:** a value left in the shell is behaviour the app can't see in its settings: whether another site may frame the page (an app embedded in a shop's admin needs `SAMEORIGIN` or `:none`), what the referrer tells other sites, how a phone lays the page out, how long a browser keeps a built file. The rest is what both ends of Groundwork, or Groundwork and showroom, must say the same.

**The price:** `:page` grows from 4 keys to 7, with `:files` a map of 3; `:server` from 8 to 15. An app moving up writes them all, and both keys on its handlers.

**Instead of:**
- the headers as one free map of extra headers: a header the app forgets is a default again, and the shell's own fixed headers could be overwritten unseen
- `:frame-options` left out because a content security policy's `frame-ancestors` can say the same: an app without a policy (`:none`) would have no way to say it
- `:files` beside `:page`: the shell lists its scripts from the same folder, one place to write it
