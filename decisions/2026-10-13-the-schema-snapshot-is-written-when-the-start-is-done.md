# The schema snapshot is written when the start is done

> Changes one sentence of [Migrations are numbered SQL files](2026-10-12-migrations-are-numbered-sql-files.md): the snapshot is no longer written right after migrating.

**Decision:** in development, the snapshot of the whole schema is written at the end of the start, after everything Groundwork installs in the database: the wall's policies, the triggers that fill a row's scope, the triggers that ring. It shows the database as it runs.

**Why:** written after migrating, the file lacked those on a database's first start and had them on every later one (they were there from the start before), so the same code wrote two different files. And the wall is the part of the schema a reviewer most needs to see change: a different permission rule now shows as a difference in the file.

**The price:** a larger file, which changes when the permission description changes.

**Instead of:** leaving out what Groundwork installs. The file would no longer be the whole schema, and cutting those parts out of the app's own tables means filtering the dump's text.
