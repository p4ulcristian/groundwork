# One start, and a migrate for the deploy

> **Changed in part** by [Every value that sets behaviour is a setting, in main too](../../2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22): `:page` has `:viewport`, `:referrer-policy`, `:frame-options` and `:files` (for `:files-folder`), every key required.

**Decision:** groundwork.server.main puts the server libraries together:

- `start-app!` takes the app as one map (`:settings :tables :changes :scope-kinds :handlers :pages :background-job-kinds :schedules`) and returns the running app; `stop-app!` stops it, server first, pool last. A start that fails stops what it had started.
- The settings are checked first against one shape, each library bringing its part (`transport :server`, `database :database`, `sessions :sessions`, `background-jobs :background-jobs`, main's own `:page`, `:version`, `:migrate-at-start?`); every key required, unknown keys refused, every problem at once.
- Groundwork's own tables (sessions, access, background jobs) go with the app's; access gives them gates, sync their change triggers.
- With `:migrate-at-start? true` (development) the start installs Groundwork's SQL functions and migrates. Otherwise it refuses a role that owns a table or passes the gates (superuser, BYPASSRLS), and a database that isn't current.
- `migrate-app!` is for the deploy, run before the start as the role that owns the tables: functions, migrate (waiting for the slow part), and grants for the app's own role.
- Handlers get access first, then sync's notes; sse lines hear the version, then sync.

**Why:** an app that takes the defaults decides nothing and gets every safety check; the production rule ([Production migrates before it starts](../../2026-10-18-production-migrates-before-it-starts.md)) is enforced, not documented.

**The price:** the start's order is fixed in main; an app that needs another composes the libraries itself.

**Instead of:** each app wiring the libraries by hand (every app repeats it, and can forget the access check); a start that migrates as the owner in production (the owner's password in the running app).
