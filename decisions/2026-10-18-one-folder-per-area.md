# One folder per area, one job per file

**Decision:** Groundwork's code is laid out by area: data (postgres, migrations, jobs), who may do what (permissions, sessions, limits), endpoints and parquery, web, browser, build, the REPL. An area's plain file is its door, the namespace apps require (`groundwork.permissions`, `groundwork.web`); the files in its folder are its own machinery, one job each (`groundwork.permissions.check`, `groundwork.web.files`). The browser store is `groundwork.parquery.store`, the leak test `groundwork.permissions.leaks`, the CSS bundles `groundwork.build.styles`, the page shell `groundwork.web.page`.

**Why:** a reader finds a thing by asking which area it belongs to, then which job. Files that grew two jobs (permissions, web, settings, the REPL, endpoints' bells) were hard to read and hard to change without touching the other job.

**The price:** apps' requires change once.

**Instead of:** a flat folder of namespaces; splitting files by size instead of by job.
