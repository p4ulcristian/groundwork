# Groundwork

An opinionated Clojure framework for web apps on Postgres, rebuilt from the
bottom as libraries. The spec is [`decisions/`](decisions/README.md).

Each library is a folder in `source-code/groundwork/<name>/`; its api is
`groundwork.<name>.api`, its parts sit in language folders beside it.
[`libraries.edn`](libraries.edn) says which library may use which, and a
test checks every require against it.

| library | api | what it is |
|---|---|---|
| wire | `groundwork.wire.api` | how a value travels as text and comes back as what it was |
| main | `groundwork.main.api` | puts the libraries together; grows as they are added |

```sh
clojure -X:test     # every library's tests, and the edges between them
```
