# Asking again, and bells that miss nothing

> **Changed in part** by [A table is one map](2026-10-22-a-table-is-one-map.md) (2026-10-22): how a table rings is said on its map.

> Adds to [Postgres rings the bell](2026-10-12-postgres-rings-the-bell.md) and [The browser keeps answers by question](2026-10-12-the-browser-keeps-answers-by-question.md).

**Decision:**

- **The store can ask a question again now.** For what no change in the database announces: a "try again" button on a failed page, a refresh button, a timer, putting a screen back after a refused write. If the question is already out, it is asked once more when the answer lands.
- **A write's answer carries its own bell.** When a write commits, the server knows which tables, scopes and ids its transaction touched, and sends that back with the answer; the writer's store re-asks what it shows of them at once. The websocket's bells stay for everyone else's screens.
- **Every table says how it rings, or the app doesn't start:** it belongs to a scope (in the permission description), it is global, it is personal, or it is silent on purpose, with the reason written. No table is quiet by accident.

**Why:** more than a third of the places a large app asked again by hand had nothing to do with a write, so asking again has to exist. For writes, bells alone make the writer wait (the bell, the gathering, then the read: the old value shows for a moment) and depend on the websocket, which some networks block; the answer already travels back to the writer, so it carries the news with nothing declared. And tables that ring nothing leave lists stale with no error; a declaration per table turns that into a refused start.

**The price:** a write whose work commits outside its endpoint's transaction tells its answer nothing; such work belongs inside the transaction. The writer may read once more when the websocket's bell for the same change arrives, unless the two share a tag.

**Instead of:** every write listing by hand what to ask again (forgotten ones leave stale screens, and the list repeats what the database knows); or bells over the websocket alone.

**Added 2026-10-14:** a statement that changed no row rings nothing (an update that matched nothing, an insert that met a row already there). Before, such a statement on a global table rang everyone watching it, and a read that made sure of a row rang its own watchers in a circle.
