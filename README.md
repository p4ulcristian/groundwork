# Groundwork

An opinionated Clojure framework for web apps on Postgres, rebuilt from the
bottom as libraries. The spec is [`decisions/`](decisions/README.md).

Each library lives in `libraries/<name>/` with its own `deps.edn`, its code
in `source-code/groundwork/<name>/` (its api `groundwork.<name>.api`, its
parts in `server_code/`, `browser_code/`), its tests in `test-code/`, its
decisions in `decisions/`. A library that uses another lists it in its own
`deps.edn`, so testing a library inside its folder proves it needs nothing
else.

| library | api | what it is |
|---|---|---|
| wire | `groundwork.wire.api` | how shaped data travels as text and comes back as what it was |
| steps | `groundwork.steps.api` | work as a list of steps over one map, until one adds an answer |
| web | `groundwork.web.api` | the server and its routes; a route's work is steps |
| sql | `groundwork.sql.api` | Postgres through a pool; every query a `.sql` file |
| background-chores | `groundwork.background-chores.api` | a queue, workers and schedules in Postgres |
| main | `groundwork.main.api` | puts the libraries together; lists every one it uses |

```sh
clojure -X:test                          # every library's tests, from here
(cd libraries/wire && clojure -X:test)   # one library alone
clojure -M:dev                           # every library, one REPL
```

An app takes all of Groundwork from git; a project that wants one library
takes it with `:deps/root "libraries/wire"`.
