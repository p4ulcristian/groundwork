# The api sits at the library's root

> Replaces [A library's api is its api file](2026-10-22-a-librarys-api-is-its-api-file.md) in where the api lives. Changes in part [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md): the language folders hold only parts, and `clojure_common/` exists only when a library has shared parts besides its api.

**Decision:** every library's api is `groundwork.<name>.api`, the file `api` at the library's root, `source-code/groundwork/<name>/api.*`. Its ending says the languages: `api.cljc` when it is the same on both sides (`groundwork.wire.api`), `api.clj` for a server-only library (`groundwork.sql.api`), `api.cljs` for a browser-only one (`groundwork.routing.api`). A library whose api differs per side has `api.clj` and `api.cljs`, one namespace with one file per language (`groundwork.main.api`: start, stop and migrate! on the server, start! in the browser); each side loads its own. The language folders, `clojure/` and `clojurescript/`, hold only the library's parts; `clojure_common/` appears only when a library has shared parts besides its api. Tests sit beside: `test/groundwork/<name>/api_test` and `api_names_test`.

**Why:** a user requires one name per library, `groundwork.<name>.api`, without knowing how it is split by language. The shared code is mostly the api itself (all 95 shared lines of `groundwork.wire`), so a folder for it alone would hold nothing else.

**The price:** a library whose api differs per side keeps two files for one namespace, which must offer what each side needs.

**Instead of:** the api inside a language folder (`groundwork.wire.clojure-common.api`, `groundwork.main.clojure.api`): the same layout for api and parts, but a user must know the language to find the api, and a two-sided library has two api names.
