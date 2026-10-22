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
| server/background-jobs | `groundwork.server.background-jobs` | what runs later? |
| server/sync | `groundwork.server.sync` | how do screens stay current? (who is told of a change) |
| server/browser-build | `groundwork.server.browser-build` | what does the browser download? (shadow-cljs; never in production) |
| server/main | `groundwork.server.main` | how does the server start? (and the page shell) |
| browser/wire | `groundwork.browser.wire` | how does data keep its types? (in the browser) |
| browser/transport | `groundwork.browser.transport` | how does the browser talk to the server? (and the pages in the bar) |
| browser/sync | `groundwork.browser.sync` | how do screens stay current? (answers kept by question, asked again) |
| browser/main | `groundwork.browser.main` | how does the browser start? |

Every namespace says its side right after `groundwork`; no file is `.cljc`.
Which library may use which is `libraries.edn`, checked by a test.

Still to come: styles (how does the page look?).

```sh
clojure -X:test                                                    # every test; Postgres ones need
                                                                   # GROUNDWORK_SQL_TEST_PORT and _USER (Postgres 16+)
clojure -X:test :dirs '["test-code/groundwork/server/database"]'   # one library
clj -A:test                                                        # one REPL, every library
```

An app takes all of Groundwork from git.
