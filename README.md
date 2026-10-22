# Groundwork

An opinionated Clojure framework for web apps on Postgres, rebuilt from the
bottom as libraries. The spec is [`decisions/`](decisions/README.md).

Each library lives in `server-code/<name>/` or `browser-code/<name>/` with its own
`deps.edn`, its code in `source-code/groundwork/<side>/<name>/`, its tests in
`test-code/`, its decisions in `decisions/`.

| folder | namespace | the question it answers |
|---|---|---|
| server-code/wire | `groundwork.server.wire` | how does data keep its types? (on the server) |
| server-code/steps | `groundwork.server.steps` | how is work written? |
| server-code/database | `groundwork.server.database` | how do I talk to Postgres, and shape its tables? |
| server-code/transport | `groundwork.server.transport` | how does the server answer the browser? (http, sse, ws, routes) |
| server-code/sessions | `groundwork.server.sessions` | who is asking? |
| server-code/access | `groundwork.server.access` | what may they do? |
| server-code/background-chores | `groundwork.server.background-chores` | what runs later? |
| server-code/main | `groundwork.server.main` | how does the server start? |
| browser-code/wire | `groundwork.browser.wire` | how does data keep its types? (in the browser) |
| browser-code/transport | `groundwork.browser.transport` | how does the browser talk to the server? (and the pages in the bar) |
| browser-code/main | `groundwork.browser.main` | how does the browser start? |

Every namespace says its side right after `groundwork`; no file is `.cljc`.

Still to come: sync on both sides (how do screens stay current?) and the
browser build (what does the browser download?).

```sh
clojure -X:test                          # every library's tests, from here
(cd server-code/wire && clojure -X:test)   # one library alone
clojure -M:dev                           # every library, one REPL
```

An app takes all of Groundwork from git; a project that wants one library
takes it with `:deps/root "server-code/wire"`.
