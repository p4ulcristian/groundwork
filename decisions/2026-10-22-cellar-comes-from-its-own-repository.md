# cellar comes from its own repository

> Changes in part [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md), [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md) and [Nine libraries, by the questions an app asks](2026-10-22-nine-libraries-by-the-questions-an-app-asks.md): the database library is no longer a folder in the tree, and its namespace, `cellar`, says no side.

**Decision:** the database library, how Groundwork talks to Postgres and shapes its tables, is a library of its own named cellar: the public repository github.com/p4ulcristian/cellar, MIT. Groundwork takes it from git by tag and sha in `deps.edn` (`io.github.p4ulcristian/cellar`, v0.1.1) and moves to a new tag on purpose. Its one public namespace is `cellar.api` (`groundwork.server.database.api` before); its tests and its own decisions live in its repository. `libraries.edn` no longer lists it; any server library may use it, and `libraries_test.clj` checks that Groundwork requires only `cellar.api`. It knows nothing of Groundwork: its bookkeeping table is `cellar.changes_done` (`groundwork.changes_done` before), the changed rows an `:on-change` function reads are `cellar_new_rows` / `cellar_old_rows` (sync's `groundwork.tell_sync` reads them under those names), and `:gates` and `:on-change` are what access and sync make and pass in. Groundwork no longer names next.jdbc, PgJDBC or HikariCP; they come with cellar.

**Why:** cellar knows nothing of Groundwork and holds one idea any Clojure app on Postgres has. Its own repository, with a `deps.edn` naming only next.jdbc, PgJDBC, HikariCP and jsonista, proves it needs nothing more, and lets an app take it alone. No deployed database had `groundwork.changes_done` yet, so its new names cost nothing.

**The price:** a change to cellar is a commit and a tag in another repository, then a bump here. Groundwork's tests no longer run cellar's. The decisions it is built on ("Database and SQL", "Migrations") stay here; the ones made inside the library moved with it.

**Instead of:**
- the database library as a folder in Groundwork with its own `deps.edn`, taken by `:local/root`: still not takeable alone from outside, and private with Groundwork
- keeping the name database, or the names quarry, postgres-maps and tables: cellar was chosen over them
