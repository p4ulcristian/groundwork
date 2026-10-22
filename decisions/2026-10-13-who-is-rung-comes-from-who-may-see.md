# Who is rung comes from who may see

> **Changed in part** by [sync tells by table and trusts the gates](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-22-sync-tells-by-table-and-trusts-the-gates.md) (2026-10-22): every open screen is told which table changed and nobody is told of a row; who may see it is decided only by the gates, when a screen asks again.

> **Changed in part** by [sync keeps screens in step with the database](2026-10-22-sync-keeps-screens-in-step.md) (2026-10-22): bells are called sync, made by groundwork.schema (the trigger) and groundwork.sync (the rest); rate limits are transport's.

> **Changed in part** by [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md) (2026-10-17): the description is the app's sentences, and a case it can't express gains a word, not a shape.

> Adds to [Postgres rings the bell](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-12-postgres-rings-the-bell.md) and [Asking again, and bells that miss nothing](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-14-asking-again-and-bells-that-miss-nothing.md).

**Decision:**

- **A table whose rows belong to a scope is in the permission description and behind the wall.** Its bell reaches the people the description lets see the row, and nobody else.
- **A global table is one whose rows belong to nobody:** reference data the app's own staff keep. It rings everyone watching it. A table with a scope column is never declared global to make it ring.
- **There is no "scope column without a wall" way to ring.** A table with a scope column stays global only when every one of its rows is read by everyone, and its declaration says so.
- When the description can't express who sees a table's rows, the description gains the shape; the table doesn't get a weaker bell meanwhile.

**Why:** a bell is correct only when it reaches everyone who can see the row. The wall is the one place where that is known. A table outside it is read by people the framework can't name (a linked scope, a visitor), so a bell aimed by its scope column alone leaves their screens stale with no error. Ringing everyone instead is correct, but one change then asks every open screen of every scope again: the extra reads grow with the square of the scopes online, which is fine at ten and fails at a thousand. Walled, the reads grow in step with the scopes, and a query that forgot its filter still can't show another scope's rows.

**The price:** every such table has to be described before the app is live, and some need a shape the description doesn't have yet. Each new way to grant access goes through the leak test.

**Instead of:**
- a bell kind "this column names the scope" for unwalled tables: fewer reads, but it misses readers outside the scope, or needs a hand-kept list of who else reads each table
- declaring scoped tables global: always correct, never scales
