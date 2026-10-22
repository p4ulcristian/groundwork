# Groundwork

An opinionated Clojure framework for web apps on Postgres, rebuilt from the
bottom as libraries. The spec is [`decisions/`](decisions/README.md).

One tree: each library is a folder `source-code/groundwork/<side>/<name>/` with
its README, its tests in `test-code/groundwork/<side>/<name>/`, its decisions in
`decisions/<side>/<name>/`; one `deps.edn` at the root.

| library | namespace | the question it answers |
|---|---|---|
| server/steps | `groundwork.server.steps` | how is work written? |
| server/transport | `groundwork.server.transport` | how does the server answer the browser? (http, sse, ws, routes) |
| server/sessions | `groundwork.server.sessions` | who is asking? |
| server/access | `groundwork.server.access` | what may they do? |
| server/sync | `groundwork.server.sync` | how do screens stay current? (who is told of a change) |
| server/main | `groundwork.server.main` | how does the server start? (and the page shell) |
| browser/transport | `groundwork.browser.transport` | how does the browser talk to the server? (and the pages in the bar) |
| browser/sync | `groundwork.browser.sync` | how do screens stay current? (answers kept by question, asked again) |
| browser/main | `groundwork.browser.main` | how does the browser start? |

Every namespace says its side right after `groundwork`; no file is `.cljc`.
Which library may use which is `libraries.edn`, checked by a test.

How data keeps its types (ids, exact decimals, days, moments) is
[wire](https://github.com/p4ulcristian/wire), a library of its own that Groundwork
takes from git; its decisions live there. So is talking to Postgres and shaping
its tables: [cellar](https://github.com/p4ulcristian/cellar), every query a
`.sql` file, tables as maps. And what runs later, background jobs on Postgres:
[backroom](https://github.com/p4ulcristian/backroom), a queue, workers and
schedules. And what the browser downloads and how the page looks:
[showroom](https://github.com/p4ulcristian/showroom), the shadow-cljs build
(watched in development, built once by the deploy) and the stylesheets a
namespace names, bundled; taken only in the build aliases, never in production.
Every Groundwork repository carries the GitHub topic `groundwork`:
`user:p4ulcristian topic:groundwork` lists them all.

Still to come: the smaller list (visitor sessions, access extras, rate limits, clean-ups).

```sh
clojure -X:test                                                    # every test; Postgres ones need
                                                                   # GROUNDWORK_SQL_TEST_PORT and _USER (Postgres 16+)
clojure -X:test :dirs '["test-code/groundwork/server/sessions"]'   # one library
clj -A:test                                                        # one REPL, every library
```

An app takes all of Groundwork from git.
