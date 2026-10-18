# Connections come from a HikariCP pool

> **Changed in part** by [Settings are required data](2026-10-12-settings-are-required-data.md) (2026-10-12): `:pool-size` has no default of 10; the app must set it.

**Decision:** `pg/start` makes a HikariCP pool with a fixed number of connections (`:pool-size`). `pg/stop` closes it.

**Why:** HikariCP is the standard JDBC pool: fast, proven, and it reconnects by itself when the database comes back. A fixed size is what HikariCP itself advises: no growing and shrinking under load.

**Instead of:** letting each app bring its own pool. More freedom, but every app would repeat the same setup.
