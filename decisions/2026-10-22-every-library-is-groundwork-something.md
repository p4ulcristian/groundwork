# Every library is groundwork.<name>, and the main one is groundwork.main

> **Changed in part** by [A library is a folder with its own deps.edn](2026-10-22-a-library-is-a-folder-with-its-own-deps.md) (2026-10-22): libraries/<name>/ with its own deps.edn, source-code, test-code, decisions.

> **Changed in part** by [Libraries are folders in one tree, their edges declared](2026-10-22-libraries-are-folders-in-one-tree.md) (2026-10-22): one source tree, libraries as folders in source-code/groundwork/, edges in libraries.edn.

> **Changed in part** by [The door is a door file, in its language's folder](2026-10-22-the-door-is-a-door-file.md) (2026-10-22): every language is a folder; the door is its `door` file.

> Changes in part [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md): the prefix is `groundwork`, not `ground`, the folders drop the `ground/` level, and the main library is `groundwork.main`, not `ground.work`.

**Decision:** the project is Groundwork, and every library in it is named `groundwork.<name>`: `groundwork.wire`, `groundwork.sql`, `groundwork.session`. A library lives in `libraries/<name>/` (the folder is the name after `groundwork.`), its code in `source-code/groundwork/<name>/`, laid out by its languages as decided (`groundwork.wire.clojure-common`, `groundwork.wire.clojure.json`, `groundwork.wire.clojurescript.time`). The main library, the one an app requires first and which composes the others, is `groundwork.main`, in `libraries/main/`. Keywords a library owns: `:groundwork.wire/id-text`, `:groundwork.session/person`. Artifacts: `io.github.p4ulcristian/groundwork-<name>`.

**Why:** one name for the project, its libraries and their namespaces: nothing new to learn, and every name says it is Groundwork's. "Main" says plainly which library is the one to start from.

**The price:** names are long (`groundwork.wire.clojurescript.time`). "main" is also Clojure's word for a program's entry point (`-main`); here it names a library, not an entry point. Today's `groundwork.*` namespaces sit beside the new ones until the apps have moved (`groundwork.web` and `groundwork.web.clojure` can both exist).

**Instead of:**
- `ground.<name>` with `ground.work` as the main library: shorter, a family of its own, but a second name next to Groundwork
- `groundwork.origin`: says where things start, but less plainly than "main"
