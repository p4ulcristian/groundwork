# A row can be a pass to one record

> **Replaced** by [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md) (2026-10-17): a pass is a `:via` sentence, which may also give an action that writes.

> Adds to [Groundwork ships permissions](2026-10-12-groundwork-ships-permissions.md). A seventh rule shape.

**Decision:** the permission description can say that a row of one table gives a scope, or a person, a reading action on the one record it names, while a condition on that row holds. A shop hands an order to another shop for one step of the work: the handoff row gives the receiving shop "see order" on that order, and on the kinds under it that the description lists, while the handoff is open.

- **The row is the pass.** Nothing else is stored. When the row's condition stops holding, or the row is gone, so is the access.
- **A pass names one record.** A link gives an action in the whole linked scope; a pass gives it on one record. The holder's other requests for that scope's records answer "not found".
- **The receiving scope's own roles decide who uses it:** a person needs an action in their own scope that the rule names, as with a link.
- **A scope can't write itself a pass.** The pass's giving scope is tied to the record's own scope by a matching link, so a pass for a record its giver doesn't own is refused by the database; and only the giving scope may create it.
- **A pass only reads.**
- **Bells:** a change to the record rings its own scope and whoever holds a pass on it.
- **The leak test** calls with no pass, and with a pass on one record asking for another.

**Why:** work moves between scopes one record at a time. A link is too wide for that (every order of the shop), and narrowing it in each query leaves the wall open behind a filter that can be forgotten. A copy of the record on the passing row would never leak, but shows the receiver the record as it was, not as it is.

**The price:** for rows outside the caller's own scopes, the wall looks the pass up. It is measured on a large table before it ships.

**Instead of:**
- a link plus a filter in every query
- copying what the receiver needs onto the passing row
- passes that write: someone outside a scope changing its rows is decided on its own
