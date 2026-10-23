# mailroom comes from its own repository

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): bridge's repository and namespaces are groundwork-bridge now.

> **Changed in part** by [mailroom is renamed bridge, and its server is Jetty 12](2026-10-23-mailroom-is-renamed-bridge-and-serves-on-jetty.md) (2026-10-23, bridge v0.7.5): the library is bridge, at github.com/p4ulcristian/bridge, its keys `:bridge/…`; its server is Jetty 12 and `:server` gains `:idle-timeout-ms` and `:threads`, `:max-header-line-bytes` becoming `:max-request-head-bytes`.

> **Changed in part** by [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22, mailroom v0.1.3): `:server` gains seven more keys, every handler writes out `:rate-limit` and `:run-steps-in`, the page carries `:sse-reconnect-after-ms` and the browser's sse line opens with it.

> **Changed in part** by mailroom's [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md) (2026-10-22, mailroom v0.1.1): main writes out all four hooks (the ws ones nil); `:server` gains `:stop-wait-ms`, `:ws-reopen-after-ms` and `:rate-limit-buckets-before-cleanup`, every key required; the page carries `:ws-reopen-after-ms` to the browser beside the addresses, and a ws line opens with it (`{:reopen-after-ms …}`).

> Changes in part [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md), [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md), [Nine libraries, by the questions an app asks](2026-10-22-nine-libraries-by-the-questions-an-app-asks.md) and [sync keeps screens in step with the database; rate limits are transport's](2026-10-22-sync-keeps-screens-in-step.md): transport and steps are no longer folders in the tree, and their namespace, `mailroom`, says no side.

**Decision:** the server's transport (`groundwork.server.transport`), the browser's (`groundwork.browser.transport`) and steps (`groundwork.server.steps`) are together one library of their own named mailroom: the public repository github.com/p4ulcristian/mailroom, MIT. Groundwork takes it from git by tag and sha in `deps.edn` (`io.github.p4ulcristian/mailroom`, v0.1.0); http-kit, reitit-ring and reitit-frontend come with it, and Groundwork no longer names them. Its one public namespace is `mailroom.api`, a `.clj` for the server and a `.cljs` for the browser; its tests and its own decisions (the transport and steps decisions, and "Three ways between browser and server", "The three ways are named http, sse and ws", "Named messages over three addresses", "Ring at the edge, steps inside", "Work is a vector of steps over one map", "web becomes transport and routes", "http-kit serves, reitit routes") live in its repository.

- **Steps moved into mailroom as a handler's steps; the name is kept.** A handler's `:steps`, each `{:name … :enter (fn [context] context)}`, and `run-steps-until-answered`, `add-answer-to-context`, `does-context-have-answer?` are as they were, through `mailroom.api`; the answer is at `:mailroom/answer`. sessions, access and sync write steps and require `mailroom.api`.
- **It knows nothing of Groundwork:** the context's keys are `:mailroom/request`, `:mailroom/message-name`, `:mailroom/message-data`, `:mailroom/notes`; a line is `{:mailroom/channel … :mailroom/request …}`; notes go out beside the answers under `:mailroom/notes` (`:groundwork/notes` before; the browser's question store reads them there), the ws ping is `{:mailroom/ping {}}`.
- **No defaults.** The addresses (`/groundwork/http`, `/groundwork/sse`, `/groundwork/ws` before), the ping interval (25 s) and the rate limit's asker key (the request's `:remote-addr`) were defaults inside transport. Now the app's `:server` settings, mailroom's shape, has every key required:

  ```clojure
  :server {:ip "0.0.0.0" :port 8080
           :client-address-header nil                   ; or "x-forwarded-for", "cf-connecting-ip"
           :addresses {:http "/groundwork/http" :sse "/groundwork/sse" :ws "/groundwork/ws"}
           :ping-every-ms 25000}
  ```

  main passes `:server` to mailroom, puts `:server :addresses` in the page for the browser, and gives the asker key (`:find-rate-limit-key`, a required option): the session's, else the address `:client-address-header` points to. A missing key refuses at start, named, with every other problem. The old `:remote-addr` fallback was wrong behind a proxy (every asker had the proxy's address); Groundwork's own key already followed the settings, and now nothing else can be used by accident.
- `libraries.edn` no longer lists `:steps` or `:transport` on either side; any library may use mailroom, through `mailroom.api` only, which `libraries_test.clj` checks.

**Why:** the three are one idea, named messages come in, a handler's steps answer them, the answers go out, and none needed anything of Groundwork. Steps live in mailroom, not on their own, because every list of steps is run by mailroom; sessions, access and sync write steps only for its handlers. Its own repository, with a `deps.edn` naming only http-kit, reitit and wire, proves it needs nothing more, and lets an app take it alone. The name: the mailroom is the room of the house where mail comes in, is sorted by name and handled, and answers go out; it stands beside the cellar, the backroom and the showroom.

**The price:** a change to mailroom is a commit and a tag in another repository, then a bump here. An app's `:server` settings grow by two keys it nearly always writes the same. Groundwork's tests no longer run mailroom's.

**Instead of:**
- transport and steps as folders in Groundwork: no app can take them alone, and nothing proves their deps
- steps as a library of their own: one user, mailroom
- renaming steps (chain, process, pipeline): "a handler's steps" already says what they are
- the names intercom, hallway and transport: mailroom was chosen over them
