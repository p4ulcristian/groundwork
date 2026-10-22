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

| library | runs on | the question it answers |
|---|---|---|
| wire | both | how does data keep its types? |
| steps | server | how is work written? |
| database | server | how do I talk to Postgres, and shape its tables? |
| transport-server | server | how does the server answer the browser? (http, sse, ws, routes) |
| transport-browser | browser | how does the browser talk to the server? (and the pages in the bar) |
| sessions | server | who is asking? |
| access | server | what may they do? |
| background-chores | server | what runs later? |
| main | both | how does it all start? (puts the others together) |

A library that has a server side and a browser side is two libraries, named
by where they run; what the two halves agree on is a contract file copied
into each, checked by a test in main.

Still to come: sync-server and sync-browser (how do screens stay current?),
and browser-build (what does the browser download?).

```sh
clojure -X:test                          # every library's tests, from here
(cd libraries/wire && clojure -X:test)   # one library alone
clojure -M:dev                           # every library, one REPL
```

An app takes all of Groundwork from git; a project that wants one library
takes it with `:deps/root "libraries/wire"`.
