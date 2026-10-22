# A library's name is its door

> Adds to [A library holds one idea and knows nothing of who uses it](2026-10-22-a-library-holds-one-idea-and-knows-nothing-of-who-uses-it.md) and [One folder per area](2026-10-18-one-folder-per-area.md) (the area's plain file is the door).

**Decision:** each `ground-*` library has one door: the namespace named after it, `ground.<library>` (`ground.wire`). The door holds only what other code uses. Everything else lives in files under it, `ground.<library>.*` (`ground.wire.time`), which are the library's insides and not for outside use. A light test in each library lists the door's public names and fails when one is added or removed, so a promise changes on purpose, never by accident.

Names:

- The namespace is short, `ground.<library>`. Uniqueness comes from the artifact name, `io.github.p4ulcristian/ground-<library>`.
- Every keyword other code reads (a key in the request map, a schema type) carries the door's namespace and is written out in full: `:ground.sessions/person`, `:ground.wire/id-text`. `::` is used only for keys that never leave their file, since inside `ground.wire.time` it would mean `:ground.wire.time/…`.

**Why:** one small file tells what a library offers; reading it is reading the library's promise. The door test keeps that promise visible under "never break". Short namespaces keep keywords readable, as in `next.jdbc`, `ring` and `malli`.

**The price:** the insides can still be required; only the convention says not to. Code moves out of one big file into several.

**Instead of:**
- a separate `api.cljc` re-exporting the insides: the name says "door", but every function is named twice and its docstring and arguments are copied
- one file with the insides marked private: nothing moves, but the door is still found by reading the whole file
- long namespaces (`io.github.p4ulcristian.ground.sessions`): unique worldwide, but every key reads `:io.github.p4ulcristian.ground.sessions/person`
