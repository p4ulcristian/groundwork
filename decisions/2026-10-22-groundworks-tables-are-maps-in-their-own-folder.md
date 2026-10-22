# Groundwork's own tables are table maps, in their own folder

> Changes in part [A job kind is a map, and the library makes its own tables](../libraries/background-jobs/decisions/2026-10-22-a-job-kind-is-a-map-and-the-library-makes-its-own-tables.md): background-jobs hands out its tables as maps; it no longer makes them.

**Decision:** a library that needs tables hands them out as table maps (`sessions-table`, `background-jobs-tables`); it never makes them. Whoever starts the app passes them to groundwork.schema with the app's own, and schema makes and changes them like any table. A table key's namespace is its Postgres schema (folder): `:groundwork/sessions` is `groundwork.sessions`, `:orders` stays in `public`. schema reads only the folders the maps name (`public` too when a map is in it), makes a folder the first time it is needed, and calls a table undeclared only inside those folders: a library's tables can be made without knowing the app's.

**Why:** one way to make every table, so a later Groundwork version can change its own tables the same way an app changes its. Groundwork's tables never clash with an app's (`sessions` of its own), access can be split by folder, and the old Groundwork's apps already keep `groundwork.sessions`.

**The price:** schema learned folders and, for background-jobs' one-per-key rule, unique keys (`{:unique? true}`: in the table when it is made, or built `CONCURRENTLY` in the slow part). A background job's params are JSON text, not `jsonb` (schema has no word for it yet).

**Instead of:**
- Groundwork's tables in `public` with a name prefix: nothing to learn, but mixed with the app's
- each library making its own with `CREATE TABLE IF NOT EXISTS`: works alone, but can never change a table
