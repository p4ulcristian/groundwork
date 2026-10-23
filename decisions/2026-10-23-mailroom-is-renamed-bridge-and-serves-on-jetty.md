# mailroom is renamed bridge, and its server is Jetty 12

> Changes in part [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md): the library's name, repository and keys, and the `:server` settings.

**Decision:** groundwork takes bridge, the library named mailroom until now, from github.com/p4ulcristian/bridge (`io.github.p4ulcristian/bridge`, v0.7.5), through `bridge.api` only; `libraries.edn` and `libraries_test.clj` say so. Every key, log event and metric it gives is named `bridge` (`:bridge/log`, `:bridge/notes`, `bridge_messages_total`, `bridge_open_lines`, …): [bridge's decision](https://github.com/p4ulcristian/bridge/blob/main/decisions/2026-10-23-mailroom-is-renamed-bridge.md). Its server is Jetty 12 through rj9a, not http-kit: [bridge's decision](https://github.com/p4ulcristian/bridge/blob/main/decisions/2026-10-23-the-server-is-jetty-12-through-rj9a.md). The app's `:server` settings change with it, every key still required:

```clojure
:server {…
         :max-request-head-bytes 8192                       ; was :max-header-line-bytes: the request line and headers together, 431
         :idle-timeout-ms        60000                      ; new: a silent connection is closed; longer than :ping-every-ms
         :threads                {:min 8 :max 50 :idle-timeout-ms 60000}   ; new: Jetty's platform threads; requests run on virtual ones
         …}
```

**Why:** mailroom read as email next to postbox; a bridge is the one way across, with a half on each side. http-kit's 2026 security fixes are only in betas; Jetty ships them as stable patch releases.

**The price:** an app's settings, dashboards and alerts change their names (`mailroom_*` is `bridge_*`), and its `:server` settings gain two keys.

**Instead of:** keeping the name and http-kit; the reasons are in bridge's two decisions.
