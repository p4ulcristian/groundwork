# A migration runs in two parts: the instant part before the start, the slow part after

> Adds to [A table is one map](2026-10-22-a-table-is-one-map.md) and [Production migrates before it starts](2026-10-18-production-migrates-before-it-starts.md).

**Decision:** migrate splits its plan in two.

- **The instant part runs before the app starts:** statements that change only Postgres's catalog, whatever a table's size. New tables, new columns (optional, or with a constant default), renames, drops, defaults, a foreign key or a check added `NOT VALID`, and the change files.
- **The slow part runs after the app has started, in the background:** statements that read or write every row. Indexes built `CONCURRENTLY`, `VALIDATE CONSTRAINT` for what the instant part added `NOT VALID`, then `SET NOT NULL` once its check is valid, and backfills in small batches. Each is retried until done; none blocks the app's writes.

The slow part is run by migrate, as the role that owns the tables, not by the app: the app's role owns nothing and can't build an index. In production the deploy starts the app once the instant part is done, and migrate goes on with the slow part. In development, with one role, migrate runs it in the background of the app's own start.

Until the slow part is done, the app's start check accepts a database that still waits for it: the instant part is what the app needs to run; the slow part makes it fast and makes the rules hold for old rows.

**Why:** a deploy then takes seconds whatever the tables' size, and no statement makes the app's queries wait while Postgres reads a big table.

**The price:** for a while after a deploy, a new index isn't there yet (its queries are slower) and an old row isn't yet proven to obey a new rule (new rows already are). A slow statement that fails (an index left invalid) is dropped and tried again by the next migrate.

**Instead of:**
- everything before the start: simplest, but a deploy waits for every index build
- the slow part as the app's background jobs: the app's role can't build indexes or validate constraints on tables it doesn't own
