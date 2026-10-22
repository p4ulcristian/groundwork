# Postgres rings the bell

> **Changed in part** by [sync keeps screens in step with the database](2026-10-22-sync-keeps-screens-in-step.md) (2026-10-22): bells are called sync, made by groundwork.schema (the trigger) and groundwork.sync (the rest); rate limits are transport's.

> **Changed in part** by [Three ways between browser and server](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-three-ways-between-browser-and-server.md) (2026-10-22): bells reach the browser over a server stream (SSE), not a websocket.

**Decision:** live updates come from the database itself. At start, Groundwork installs a trigger on every table in the permission description (and on global and person-owned tables): after a statement changes rows, it announces the table, the scopes of the changed rows (from their own scope column) and their ids, through Postgres's NOTIFY. Postgres delivers announcements only when the transaction commits, once per distinct message. Every server listens; each rings its own open browser lines. Groundwork learns at start which tables each read uses by asking Postgres to plan its SQL.

A screen refreshes when a table it reads changed in its scope: a list on any change there; a screen for one record only when its own id changed. Person-owned tables ring only that person's screens; global tables ring every screen reading them. The browser gathers bells for a moment and re-asks only what is on screen, through the endpoints, with their permission checks. The bell carries no data.

**Why:** nothing to declare, nothing to forget. Writes from endpoints, webhooks, background jobs, cascades and hand-run fixes all ring, and a rolled-back transaction rings nothing. The scope filter keeps it targeted ("jobs in this shop", not "jobs"), and ids make one-record screens exact.

**The price:** lists refresh on any change to their table in their scope, even when the change doesn't affect them: extra reads, never wrong data. A read that calls a database function hides its tables from the planner and names them with a label.

**Instead of:**
- labels on every endpoint (what it shows, what it touches): precise intent, but a forgotten label leaves a screen silently stale, and changes outside endpoints ring nothing
- checking each list's filter against the changed rows (live queries): exact, but re-runs queries per change
- the database's change stream: row-level with before and after, but a replication slot that fills the disk if its listener stops
- polling, or a sync engine with its own permission rules
