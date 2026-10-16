# Who may see what is the app's; Groundwork enforces it

> How the app says it: [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md).

> Changes [Groundwork ships permissions](2026-10-12-groundwork-ships-permissions.md) in part. Decided, not built: Groundwork is frozen ([Groundwork is frozen](2026-10-15-groundwork-is-frozen.md)).

**Decision:** the line between the business and everything else runs through permissions too.

- **The app's:** who may see and do what. Which records a partner reaches, whether platform staff see every scope, what a shared record gives the other side, which rows everyone may read. The app says it in its own words, next to its own tables.
- **Groundwork's:** enforcing it, the same for every app. The wall in Postgres, telling the database who is asking, refusing a database role that passes by the wall, an endpoint without a permission doesn't start, every id in a request checked, "not found" for another scope's record, and the leak test that proves nothing crosses.

How the app says it is not decided yet. The bells learn who to ring from it too, so they follow whatever form it takes.

**Why:** Groundwork's rule shapes tried to answer business questions in the framework's own vocabulary. Every new business case became a new shape and a Groundwork decision, and the one large app still didn't fit after six new shapes in one day. A business case belongs to the business; only how it is enforced is shared.

**The price:** each app writes its own access rules, so a wrong rule is the app's mistake; the wall and the leak test are what catch it.

**Instead of:** a general shape for every way of granting access, decided and built in Groundwork one at a time as apps ask for them.
