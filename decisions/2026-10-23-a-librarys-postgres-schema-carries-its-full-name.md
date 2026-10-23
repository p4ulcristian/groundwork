# A library's Postgres schema carries its full name

> Follows [A library's keys and metrics carry its full name](2026-10-23-a-librarys-keys-and-metrics-carry-its-full-name.md) and [Each library's plain name](2026-10-23-each-librarys-plain-name.md).

**Decision:** a library's tables stay in a schema of its own, named `groundwork_` and its name with underscores: `doorman.sessions` becomes `groundwork_access.sessions`, `attic.files` `groundwork_file_storage.files`, `postbox.emails` `groundwork_email.emails`, `backroom.background_jobs` `groundwork_background_jobs.background_jobs`. The tables keep their names. groundwork's own schema stays `groundwork` (`groundwork.run_once_keys`).

**Why:** the same rule as namespaces, keys and metrics. Plain names make schemas an app could have too (`email`, `access`), and `postgres` would read oddly as a schema; under `groundwork_` they never collide, and `\dn` lists groundwork's schemas together. Each library still owns its schema.

**The price:** the longest names in SQL, mostly in the libraries' own `.sql` files; a database that has the old schemas needs them renamed once.

**Instead of:**
- bare plain schemas (`access.sessions`): short like today, but open to collisions with an app's schemas
- one `groundwork` schema with prefixed tables (`groundwork.access_sessions`): one place, but a library no longer owns its schema and every table is renamed too
