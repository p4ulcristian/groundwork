# groundwork.database decisions

The decisions of the groundwork.database library (groundwork.sql and groundwork.schema until 2026-10-22, see [Nine libraries](../../../decisions/2026-10-22-nine-libraries-by-the-questions-an-app-asks.md)). Decisions about Groundwork as a whole are in [the top decisions](../../../decisions/README.md); the rules for these files are there too.

Queries: the older decisions about SQL (the pool, TLS, time limits, names, real types) are in the top folder, under "Database and SQL".

- [A query is made by a function from its .sql file](2026-10-22-a-query-is-made-by-a-function-from-its-sql-file.md)

Tables: the ones they are built on are in the top folder: [A table is one map](../../../decisions/2026-10-22-a-table-is-one-map.md), [A table map says its column types in wire's words](../../../decisions/2026-10-22-a-table-map-says-column-types-in-wires-words.md), [A migration runs in two parts](../../../decisions/2026-10-22-a-migration-runs-in-two-parts.md), [A fresh database takes the maps as they are](../../../decisions/2026-10-22-a-fresh-database-takes-the-maps-as-they-are.md), [Groundwork's own tables are table maps, in their own folder](../../../decisions/2026-10-22-groundworks-tables-are-maps-in-their-own-folder.md).

- [Changes are data, and v1 makes tables, columns, references, enums and indexes](2026-10-22-changes-are-data-and-what-v1-makes.md)
- Gates (row-level security from a map's `:gates`) and `grant-tables-to-role!`: decided in groundwork.auth, [access piece 4](../../auth/decisions/2026-10-22-access-piece-4-the-gates.md)
