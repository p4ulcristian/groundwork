# One tree; libraries.edn says, and a test checks, who uses whom

> **Changed in part** by [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md) (2026-10-22): sessions and access are doorman, one library of its own taken from git, one public namespace `doorman.api` with no side.

> **Changed in part** by [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md) (2026-10-22): transport (both sides) and steps are mailroom, a library of its own taken from git, one public namespace `mailroom.api` with no side, a `.clj` for the server and a `.cljs` for the browser.

> **Changed in part** by [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md) (2026-10-22): the browser build and styles libraries are showroom, a library of its own taken from git, one public namespace `showroom.api` with no side, taken only in the build aliases.

> **Changed in part** by [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md) (2026-10-22): the background-jobs library is backroom, a library of its own taken from git, one public namespace `backroom.api` with no side.

> **Changed in part** by [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md) (2026-10-22): the database library is cellar, a library of its own taken from git, one public namespace `cellar.api` with no side.

> **Changed in part** by [wire comes from its own repository](2026-10-22-wire-comes-from-its-own-repository.md) (2026-10-22): wire is a library of its own, taken from git, one namespace `wire` with no side.

> **Changed in part** by [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md) (2026-10-22): the addresses and the pages are said once, on the server, and reach the browser in the page; the `contract.cljc` copies are gone.

> Replaces [A library is a folder with its own deps.edn](2026-10-22-a-library-is-a-folder-with-its-own-deps.md). Changes in part [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md) and [No Polylith tool](2026-10-22-no-polylith-tool.md). Brings back what [Libraries are folders in one tree, their edges declared](2026-10-22-libraries-are-folders-in-one-tree.md) decided.

**Decision:**

- **One tree:** `source-code/groundwork/server/<library>/` and `source-code/groundwork/browser/<library>/`, tests in `test-code/groundwork/<side>/<library>/`, one `deps.edn` at the root. A library's README sits in its folder; its decisions in `decisions/<side>/<library>/`. Each library's name appears once in the tree.
- **`libraries.edn`** lists every library by side and the libraries each may use. `test-code/groundwork/libraries_test.clj` reads every namespace's requires and fails when a library requires one it doesn't list, requires another library's parts instead of its `api`, or requires the other side's code; and when two libraries use each other around a circle.
- **Testing one library alone:** `clojure -X:test :dirs '["test-code/groundwork/server/database"]'`.

**Why:** with a deps.edn per library, Clojure puts each library's name twice in every path (its folder, then its namespace's folder), as in every Clojure monorepo; one tree says it once. What a deps.edn per library proved, that a library uses nothing it doesn't name, the test proves from the requires.

**The price:** one library can't be taken alone from git; an app takes Groundwork whole. All dependencies are in one deps.edn, so an app gets every library's (a database driver even without the database). The check is of requires: code loaded by a computed name at runtime would pass it, and Groundwork has none.

**Instead of:**
- a deps.edn per library: each library alone and its deps proven by the classpath, but its name twice in every path
- only the README saying who uses whom: nothing would catch a wrong require
