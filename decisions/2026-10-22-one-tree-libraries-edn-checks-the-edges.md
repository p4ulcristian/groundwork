# One tree; libraries.edn says, and a test checks, who uses whom

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
