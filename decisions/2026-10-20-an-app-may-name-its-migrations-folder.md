# An app may name its migrations folder

> Adds to [Migrations are numbered SQL files](2026-10-12-migrations-are-numbered-sql-files.md).

**Decision:** an app's migrations are in `migrations/` on the classpath unless its settings name another folder, `:migrations :folder "catalog-migrations/"`. Groundwork's own still run first. The start, migrate and production's check all read the same folder.

**Why:** two apps can be built from one codebase and share its classpath, each with its own database: Iron Rainbow and its catalog app. With one fixed folder the second app would run the first one's migrations on its own database.

**Instead of:** a classpath of its own for the second app (its own resources root, an image laid out twice), or the second app replacing Groundwork's migrations part.
