# An app's table that writes its own :gates or :on-change is refused

> The user's decision, 2026-10-23.

**Decision:** an app's table map says `:access` and `:doorbell`, never `:gates` or `:on-change`: doorman's `put-gates-on-tables` writes `:gates` from `:access`, and doorbell's `put-doorbell-on-tables` writes `:on-change` from `:doorbell`. `start-app!` (before anything starts), `make-ready-tables` and `migrate-app!` refuse an app's table that has either key, even nil, naming each table and key at once (`:house.server.main/bad-tables`), as doorman refuses a handler's own `:run-steps-in`. house's, doorman's and backroom's own tables are not checked: house puts them with the app's.

**Why:** both functions `assoc` over what is there, so a table's own row rules or triggers would be replaced without a word, and the app would believe its rule holds.

**The price:** an app that wants a row rule beyond the sentences can't write it on the map; it says it as a sentence, or waits for doorman to take one.

**Instead of:**
- keeping the app's own when present: two sources of one gate, and a sentence that silently does nothing
- a warning in the log: read after the deploy, if at all
