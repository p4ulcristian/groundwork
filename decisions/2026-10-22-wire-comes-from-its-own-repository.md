# wire comes from its own repository

> Changes in part [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md) and [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md): wire is no longer a folder in the tree, and its namespace, `wire`, says no side.

**Decision:** wire, how data keeps its types between the edges of an app, is a library of its own: the public repository github.com/p4ulcristian/wire, MIT. Groundwork takes it from git by tag and sha in `deps.edn` (`io.github.p4ulcristian/wire`, v0.1.0 first) and moves to a new tag on purpose. It is one namespace for both languages, `wire.api`, and its decisions live in its repository. `libraries.edn` no longer lists it; any Groundwork library may use it, and `libraries_test.clj` checks that Groundwork requires only `wire.api`.

**Why:** wire knows nothing of Groundwork and holds one idea any Clojure app with a browser has. Its own repository, with a `deps.edn` naming only malli and jsonista, proves it needs nothing more, and lets an app take it alone. Its server and browser api files were the same but for their names; one `.cljc` can't drift.

**The price:** a change to wire is a commit and a tag in another repository, then a bump here. Groundwork's tests no longer run wire's.

**Instead of:**
- wire as a folder in Groundwork with its own `deps.edn`, taken by `:local/root`: still not takeable alone from outside, and private with Groundwork
- one `deps.edn` with `:paths ["groundwork" "wire"]`: still one library, one set of deps
