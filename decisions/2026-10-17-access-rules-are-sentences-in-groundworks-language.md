# Access rules are sentences in Groundwork's language

> **Changed in part** by doorman's [Access rules are sentences in doorman's language](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-17-access-rules-are-sentences-in-doormans-language.md) (2026-10-22): the language, its words and what the wall, the check, the list filters and the leak test make of them moved there whole. What stays here is what the sentences give beyond doorman.

> **Changed in part** by [A table is one map](2026-10-22-a-table-is-one-map.md) (2026-10-22): a table's sentences sit on its map, and its record kind (table, id, whose, parent) is read from the map.

> Built under [The freeze lifts for the access language](2026-10-17-the-freeze-lifts-for-the-access-language.md).

**Decision:** an app writes who may do what as one list of sentences, doorman's, on its table maps. Beyond doorman, Groundwork makes from the same sentences:

- **who is rung** when a row changes: since doorbell's [sync tells by table and trusts the gates](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-22-sync-tells-by-table-and-trusts-the-gates.md), nobody: every open screen hears the table, and the gates, made from the sentences, decide what each gets when it asks again;
- **the browser's "can I?"**: not built yet.

**Why:** what the app says once, in one place, is what every part reads; no part keeps its own copy of the rules.

**Instead of:** each part (the bells, the browser) with its own description of who may see what.
