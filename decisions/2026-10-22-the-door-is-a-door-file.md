# The door is a door file, in its language's folder

> Changes in part [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md) and [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md): every language is a folder, `clojure-common` too, and the door is the file `door` in one of them.

**Decision:** in every library, each language is a folder under `source-code/groundwork/<name>/`: `clojure_common/`, `clojure/`, `clojurescript/`. A library's door is the file `door` in the language its users use: `groundwork.wire.clojure-common.door` (both sides), `groundwork.sql.clojure.door` (server only), `groundwork.routing.clojurescript.door` (browser only). A library used differently on each side has a door in each: `groundwork.main.clojure.door` and `groundwork.main.clojurescript.door`. The other files in those folders are the library's parts. Tests mirror the layout: `test/groundwork/<name>/clojure_common/door_test.cljc`, and the test of the door's promise beside it.

**Why:** the three languages are laid out the same way, and the door has the same name in every library, so it is found without looking.

**The price:** one more folder level (`groundwork.wire.clojure-common.door`).

**Instead of:** `clojure_common.cljc` as a file beside the `clojure/` and `clojurescript/` folders: one level less, but the three languages laid out two ways.
