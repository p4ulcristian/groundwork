# Libraries are folders in one tree, their edges declared

> **Replaced** by [A library is a folder with its own deps.edn](2026-10-22-a-library-is-a-folder-with-its-own-deps.md) (2026-10-22): each library its own folder and deps.edn, Polylith's layout without its tool.

> Changes in part [The ground-* libraries live in one repo](2026-10-22-the-ground-libraries-live-in-one-repo.md), [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md), [The api sits at the library's root](2026-10-22-the-api-sits-at-the-librarys-root.md) and [Each library keeps its own decisions](2026-10-22-each-library-keeps-its-own-decisions.md): no folder or `deps.edn` per library; one source tree, and the edges between libraries are data checked by a test.

**Decision:** Groundwork has one source tree, `source-code/`, and one `deps.edn`. Each library is a folder in it, `source-code/groundwork/<name>/`, so every name appears once: `source-code/groundwork/wire/api.cljc` is `groundwork.wire.api`; its parts sit in its language folders (`groundwork.wire.clojure.json`, `groundwork.wire.clojurescript.time`). A library's tests are in `test/groundwork/<name>/`, its decisions in `decisions/<name>/`, its README beside its api. `libraries.edn`, at the repo's root, says for each library which others it may use (`{wire {:uses []} main {:uses :all}}`); a test reads every source file's requires and fails when a library requires one it may not. The namespaces of today's Groundwork that aren't a library yet belong to `main`.

**Why:** the structure is as clean as Clojure allows: one folder per library, each name once. The edges stay real, because they are written down as data and checked on every run.

**The price:** a project can't take one library alone with only its own dependencies; depending on Groundwork brings every library's dependencies. A library's own dependency list isn't written anywhere; `deps.edn` holds them all.

**Instead of:** a folder and `deps.edn` per library (`libraries/wire/source-code/groundwork/wire/…`): each library takeable alone and its edges enforced by the classpath, but every library's name appears twice in every path.
