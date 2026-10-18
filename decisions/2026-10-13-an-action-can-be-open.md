# An action can be open

> **Replaced** by [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md) (2026-10-17): an action is open through a sentence that gives it `:to :anyone` or `:to :signed-in`, and such a sentence may write when a `:when` or `:via` narrows it.

> Adds to [Groundwork ships permissions](2026-10-12-groundwork-ships-permissions.md) and [Who is rung comes from who may see](2026-10-13-who-is-rung-comes-from-who-may-see.md).

**Decision:** the permission description can mark a reading action as open: to anyone, or to anyone signed in. An open action needs no membership: a request for it is about the scope it names, that scope is verified for the transaction, and the wall shows the caller only the kinds of record the action has a rule for, as that rule allows (a condition such as "only while published" included). Everything else in the scope stays visible to members only.

- The open actions are one list in the description.
- An open action only reads. No role holds it, and it can't be granted or taken away.
- A watch on such a read holds the scope, so its bell is the scope's bell: a visitor on one shop's page is asked again when that shop's rows change, and not when another shop's do.
- The leak test calls every open action as a stranger and expects only the rows its rule allows, and nothing of any other kind.

**Why:** some of a scope's rows are meant for people outside it: a shop's catalogue on its public page, its name on a customer's order. Reads like these ran as the system, past the wall, with the query's own filter as the only protection, on the endpoints the whole internet can call. An open action puts them behind the same wall as every other read and lets the bell know which scope a visitor is looking at.

**The price:** marking the wrong action open publishes those rows in every scope. The list is short and in one place, and the leak test reads it.

**Instead of:**
- a column that marks rows public: nothing says which scope a visitor is looking at, so their bell is everyone's
- running such reads as the system and telling the bell the scope by hand: the most exposed endpoints keep the least protection
- open writes: someone outside a scope writing into it is a different risk, decided on its own
