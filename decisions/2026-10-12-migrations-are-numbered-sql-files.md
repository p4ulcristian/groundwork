# Migrations are numbered SQL files

> **Changed in part** by [The schema snapshot is written when the start is done](2026-10-13-the-schema-snapshot-is-written-when-the-start-is-done.md) (2026-10-13): the development snapshot is written at the end of the start, not right after migrating.

> **Changed in part** by [Production migrates before it starts](2026-10-18-production-migrates-before-it-starts.md) (2026-10-18): in production migrate runs the migrations before the start, and the start only checks they ran.

**Decision:** an app's migrations are numbered plain SQL files in `resources/migrations/` (`001_create_shops.sql`, `002_…`). At start Groundwork takes a lock in Postgres, runs its own new migrations first and then the app's, in number order, each in its own transaction, and remembers each file with its fingerprint. A failed file rolls back and the app doesn't start. A file that already ran and was changed afterwards stops the start: a fix is a new file. In development, Groundwork writes a snapshot of the whole schema into one file after migrating.

**Why:** one mechanism, and nothing is ever dropped unless a migration says so. Every change is explicit and runs once. The snapshot keeps the whole current schema visible in one place.

**Instead of:** schema files that a tool converges the database to, plus migrations for renames and data changes. Additive changes need no migration there, but it's two mechanisms that must agree, an outside tool, and the tool drops whatever the files don't describe, so it always needs a guard.
