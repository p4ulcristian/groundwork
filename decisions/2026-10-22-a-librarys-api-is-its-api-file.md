# A library's api is its api file

> **Replaced** by [The api sits at the library's root](2026-10-22-the-api-sits-at-the-librarys-root.md) (2026-10-22): the api is groundwork.<name>.api at the library's root.

> Replaces [The door is a door file, in its language's folder](2026-10-22-the-door-is-a-door-file.md). Changes in part [A library's name is its door](2026-10-22-a-librarys-name-is-its-door.md): what other code uses is called the library's api, and lives in its api file.

**Decision:** what a library offers to other code is its **api**, and it lives in the file `api` in the folder of the language its users use: `groundwork.wire.clojure-common.api` (both sides), `groundwork.sql.clojure.api` (server only), `groundwork.routing.clojurescript.api` (browser only). A library used differently on each side has an api on each: `groundwork.main.clojure.api` and `groundwork.main.clojurescript.api`. The other files in the language folders are the library's parts, not for outside use. Tests mirror the layout: `api_test` for what the api does, `api_names_test` for its public names (adding or removing one fails it, so the api changes on purpose). Where other decisions say "door", read "api".

**Why:** "api" is the word everyone already knows for what a library offers; the file is named by what it is.

**The price:** older decisions keep the word "door" in their text.

**Instead of:**
- `door`: Groundwork's own word, but one more word to learn for what everyone calls an api
- `core`: Clojure's oldest habit, but it says "main", not "what others use"
- `interface` (Polylith's word): close in meaning, but longer and also a Java keyword
