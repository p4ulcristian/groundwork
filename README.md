# Groundwork

An opinionated Clojure framework for web apps on Postgres, rebuilt from the
bottom as libraries. The spec is [`decisions/`](decisions/README.md).

Each library lives in `libraries/<name>/` with its own `deps.edn`, its code
in `source-code/groundwork/<name>/` (its api `groundwork.<name>.api`, its
parts in `server_code/`, `browser_code/`), its tests in `test-code/`, its
decisions in `decisions/`. A library that uses another lists it in its own
`deps.edn`, so testing a library inside its folder proves it needs nothing
else. A library uses only libraries in a lower layer (no circles); main's
layers test checks it.

| library | api | what it is |
|---|---|---|
| wire | `groundwork.wire.api` | how shaped data travels as text and comes back as what it was |
| steps | `groundwork.steps.api` | work as a list of steps over one map, until one adds an answer |
| transport | `groundwork.transport.api` | named messages over http, sse and ws, at Groundwork's three addresses |
| routes | `groundwork.routes.api` | pages by name on both sides, and the one Ring handler (reitit) |
| sql | `groundwork.sql.api` | Postgres through a pool; every query a `.sql` file |
| background-chores | `groundwork.background-chores.api` | a queue, workers and schedules in Postgres |
| schema | `groundwork.schema.api` | tables as maps; Postgres brought to them |
| sessions | `groundwork.sessions.api` | who is asking: a session in Postgres, a cookie in the browser |
| access | `groundwork.access.api` | who may see and do what (pieces 1-4: who belongs where; sentences into SQL; the check; the gates) |
| main | `groundwork.main.api` | puts the libraries together; lists every one it uses |

```sh
clojure -X:test                          # every library's tests, from here
(cd libraries/wire && clojure -X:test)   # one library alone
clojure -M:dev                           # every library, one REPL
```

An app takes all of Groundwork from git; a project that wants one library
takes it with `:deps/root "libraries/wire"`.
