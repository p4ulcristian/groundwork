# A library is laid out by its languages

> **Changed in part** by [The door is a door file, in its language's folder](2026-10-22-the-door-is-a-door-file.md) (2026-10-22): every language is a folder; the door is its `door` file.

> **Changed in part** by [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md) (2026-10-22): the prefix is groundwork, folders are libraries/<name>, the main library is groundwork.main.

> Replaces [A library is named by its languages](2026-10-22-a-library-is-named-by-its-languages.md): the languages are spelled out, the libraries share the `ground` namespace, and parts sit in a folder per language.

**Decision:** Groundwork is the whole repo; its libraries live in `libraries/ground/<name>/`, each with its own `deps.edn` and one source folder, `source-code/`. A library's namespaces start with `ground.<name>`, so the folder and the name match: `libraries/ground/wire` is `ground.wire`. Below that, code is laid out by the language it is written in, spelled out:

- `ground.<name>.clojure-common` (`clojure_common.cljc`): written once for both languages
- `ground.<name>.clojure` (`clojure.clj`): Clojure only, on the JVM
- `ground.<name>.clojurescript` (`clojurescript.cljs`): ClojureScript only, in the browser or on Node

The door is the one of the three the library has for its users: `clojure-common` when both sides use it (`ground.wire.clojure-common`), `clojure` for a server-only library (`ground.sql.clojure`), `clojurescript` for a browser-only one (`ground.routing.clojurescript`). Parts go in a folder per language: `ground.wire.clojure.json`, `ground.wire.clojurescript.time`. The keywords a library owns carry its name, written out: `:ground.wire/id-text`, `:ground.session/person`. The artifact is `io.github.p4ulcristian/ground-<name>`. Groundwork's recipe is one of the family: `ground.work`, in `libraries/ground/work/`, laid out the same way (`ground.work.clojure-common`); the repo's top holds only `libraries/`, `decisions/` and the repo's `deps.edn`. The old `groundwork.*` names keep working, pointing at the new ones, until the apps have moved.

**Why:** every folder and name says in which language its code runs, the same way in every library. The libraries read as one family, `ground.wire`, `ground.sql`, as Clojure's own `clojure.string` and `clojure.set` do, and a library's folder is its namespace.

**The price:** names are long (`ground.wire.clojurescript.time`). Several libraries put files under the same `ground/` folder on the classpath; no two may define the same namespace. Clojure tools look for `src` by default; every build names `source-code` itself. Where tests live is not decided yet.

**Instead of:**
- short endings (`ground-wire.clj`, `.cljs`, `.cljc`): short, but cryptic, and each library its own top name
- one file per language (`ground.wire.clojure`): simple, but the files grow big
- languages as root folders with names free of them (`ground-wire.json` twice): short names, but the language shows only in the folder
- `ground-wire` (a dash): one name per library, but no family and the folder doesn't read as the name
