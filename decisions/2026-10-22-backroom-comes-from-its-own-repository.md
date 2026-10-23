# backroom comes from its own repository

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): backroom is groundwork-background-jobs now (github.com/p4ulcristian/groundwork-background-jobs, `groundwork-background-jobs.api`).

> Changes in part [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md), [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md), [Nine libraries, by the questions an app asks](2026-10-22-nine-libraries-by-the-questions-an-app-asks.md) and [The background work is called background jobs](2026-10-22-the-background-work-is-called-background-jobs.md): the background-jobs library is no longer a folder in the tree, and its namespace, `backroom`, says no side.

**Decision:** the background-jobs library, what runs later (a queue, workers and schedules, in Postgres), is a library of its own named backroom: the public repository github.com/p4ulcristian/backroom, MIT. Groundwork takes it from git by tag and sha in `deps.edn` (`io.github.p4ulcristian/backroom`, v0.1.0) and moves to a new tag on purpose. Its one public namespace is `backroom.api` (`groundwork.server.background-jobs.api` before), its part `backroom.cron`; its tests and its own decisions ("A job kind is a map, and the library makes its own tables", "A job can give up for good") live in its repository. `libraries.edn` no longer lists it; any server library may use it, and `libraries_test.clj` checks that Groundwork requires only `backroom.api`. It knows nothing of Groundwork: its tables are `backroom.background_jobs` and `backroom.background_job_schedules` (`groundwork.…` before), what it throws is typed `:backroom/…`, and its table maps say nothing of sync: main makes them `[:silent "no screen shows background jobs"]` when it puts them with Groundwork's own. main's `migrate-app!` grants them to the app's own role through cellar's `grant-tables-to-role!`, so the folder `backroom` is granted with the others. The settings key stays `:background-jobs`, checked by `backroom/settings-shape`.

Only the library is called backroom. A unit of work is still a **background job** everywhere ([GLOSSARY.md](../GLOSSARY.md)).

**Why:** backroom knows nothing of Groundwork and holds one idea any Clojure app on Postgres has. Its own repository, with a `deps.edn` naming only cellar and wire, proves it needs nothing more, and lets an app take it alone. No deployed database had `groundwork.background_jobs` yet, so its new names cost nothing. The name: the back room is where work gets done out of sight, and it sits next to cellar.

**The price:** a change to backroom is a commit and a tag in another repository, then a bump here. Groundwork's tests no longer run backroom's. The decisions it is built on that are about Groundwork as a whole, and the naming of a background job, stay here; the ones made inside the library moved with it.

**Instead of:**
- the library as a folder in Groundwork: no app can take it alone, and nothing proves its deps
- keeping the name background-jobs, or the names errand and conveyor: backroom was chosen over them
