# A fresh database takes the maps as they are

> Adds to [A table is one map](2026-10-22-a-table-is-one-map.md).

**Decision:** when migrate meets a database that has none of the app's tables and has run no change file, it makes every table straight from the maps and marks every change file done without running it. On a database that already has the app's tables, a change file whose table or column isn't there stops migrate with a message: something differs that nobody declared.

**Why:** the maps already say each table's final shape; a rename or a drop from the past has nothing to act on in a new database. Marking them done means a new developer's database, a test database and production all end with the same list of change files done.

**The price:** a change file that also moves data (a backfill) doesn't run on a fresh database; there is no data to move.

**Instead of:**
- skipping each change file whose target is missing, on any database: also works on a fresh one, but on a live database it would hide a real mismatch
- running every change file from the first, then comparing: the old tables would have to be built first, which is what numbered migrations did
