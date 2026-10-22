# Groundwork

An opinionated Clojure framework for web apps on Postgres, rebuilt from the
bottom as libraries. The spec is [`decisions/`](decisions/README.md).

One tree: each library is a folder `source-code/groundwork/<side>/<name>/` with
its README, its tests in `test-code/groundwork/<side>/<name>/`, its decisions in
`decisions/<side>/<name>/`; one `deps.edn` at the root.

| library | namespace | the question it answers |
|---|---|---|
| server/wire | `groundwork.server.wire` | how does data keep its types? (on the server) |
| server/steps | `groundwork.server.steps` | how is work written? |
| server/database | `groundwork.server.database` | how do I talk to Postgres, and shape its tables? |
| server/transport | `groundwork.server.transport` | how does the server answer the browser? (http, sse, ws, routes) |
| server/sessions | `groundwork.server.sessions` | who is asking? |
| server/access | `groundwork.server.access` | what may they do? |
| server/background-chores | `groundwork.server.background-chores` | what runs later? |
| server/main | `groundwork.server.main` | how does the server start? |
| browser/wire | `groundwork.browser.wire` | how does data keep its types? (in the browser) |
| browser/transport | `groundwork.browser.transport` | how does the browser talk to the server? (and the pages in the bar) |
| browser/main | `groundwork.browser.main` | how does the browser start? |

Every namespace says its side right after `groundwork`; no file is `.cljc`.
Which library may use which is `libraries.edn`, checked by a test.

Still to come: sync on both sides (how do screens stay current?) and the
browser build (what does the browser download?).

```sh
clojure -X:test                                                    # every test
clojure -X:test :dirs '["test-code/groundwork/server/database"]'   # one library
clj -A:test                                                        # one REPL, every library
```

An app takes all of Groundwork from git.
