# Production migrates before it starts

> Changes [Migrations are numbered SQL files](2026-10-12-migrations-are-numbered-sql-files.md) in part: in production, migrations no longer run at start.

**Decision:** in production two database roles do two jobs at two moments.

- **Migrate**, run by the deploy before the app starts, connects as the role that owns the tables. It runs Groundwork's and the app's new migrations and installs everything Groundwork puts into the database from the app's settings: the wall, its functions, the guards and fills, the bells' triggers.
- **The app** starts as a role that owns nothing and cannot pass by the wall. At start it only checks that the database is current: every migration ran and what migrate installed matches the app's settings. Otherwise it refuses to start and says to migrate.

Groundwork refuses to start an app whose role owns a table or can bypass row-level security. In development and tests, start still migrates, with one role.

**Why:** the role that serves requests should not be able to change the tables. If a request were ever tricked into running SQL, an owner could switch the wall off or drop a table. The running app never holds the owner's password.

**The price:** a deploy has one more step, and production needs two roles.

**Instead of:** two roles in the app's settings, the start migrating as the owner first (the owner's password would sit in the running app); one role that owns the tables and serves requests.
