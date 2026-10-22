# sync keeps screens in step with the database; rate limits are transport's

> **Changed in part** by [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md) (2026-10-22): rate limits are mailroom's, and the asker key is the app's to give (Groundwork gives the session's, else the address the `:server :client-address-header` setting points to).

> Changes in part [Postgres rings the bell](2026-10-12-postgres-rings-the-bell.md), [Who is rung comes from who may see](2026-10-13-who-is-rung-comes-from-who-may-see.md), [Asking again, and bells that miss nothing](2026-10-14-asking-again-and-bells-that-miss-nothing.md) and [The first cut is thirteen libraries](2026-10-22-the-first-cut-is-thirteen-libraries.md): "bells" and "ringing" are called sync; bells and the browser queries are one library; rate limits go into transport.

**Decision:**

- **The idea is called sync**: keeping what screens show in step with the database. "Bell", "ring" and "rings" go. The library is `groundwork.sync`.
- **A table map says how its changes reach screens** with `:sync`: `:by-access` (the people who may see the row), `:everyone`, `:person`, or `[:silent "why"]`.
- **The message** is `{:sync/changed {:table "orders" :ids […]}}`: a row changed. The server invalidates nothing; the browser decides which answers it kept may be out of date and asks for them again.
- **The parts:** the trigger in Postgres is made by groundwork.schema from the table map's `:sync`, as it makes a gate ("trigger" stays the word for that mechanism only). groundwork.sync holds the rest: the listener, who to tell (from the access sentences), sending over sse, and in the browser the answers kept by question and asked again (what was planned as the browser-queries library).
- **Rate limits** are a handler's, in groundwork.transport (a limit on a message name), not a library of their own.

**Why:** "bells" named the mechanism and needed explaining; sync names what the user gets. The listener, telling, sending and asking again only work together, so they are one library; a trigger is made from a table map, so schema makes it. A limit is on a message, and the handler table is transport's.

**Instead of:** invalidate (what the browser does, not what the message says); refresh; live (says "real time"); notifications (clashes with an app's notifications to people); bells and browser queries as two libraries.
