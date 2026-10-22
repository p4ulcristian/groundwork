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

| library | api | the question it answers |
|---|---|---|
| wire | `groundwork.wire.api` | how does data keep its types? |
| steps | `groundwork.steps.api` | how is work written? |
| database | `groundwork.database.api` | how do I talk to Postgres, and shape its tables? |
| transport | `groundwork.transport.api` | how do browser and server talk, at which addresses? |
| auth | `groundwork.auth.api` | who is asking, and what may they do? |
| background-chores | `groundwork.background-chores.api` | what runs later? |
| main | `groundwork.main.api` | how does it all start? (puts the others together) |

Still to come: sync (how do screens stay current?) and browser-build (what does the browser download?).

```sh
clojure -X:test                          # every library's tests, from here
(cd libraries/wire && clojure -X:test)   # one library alone
clojure -M:dev                           # every library, one REPL
```

An app takes all of Groundwork from git; a project that wants one library
takes it with `:deps/root "libraries/wire"`.
