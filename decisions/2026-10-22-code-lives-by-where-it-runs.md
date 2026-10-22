# Code lives by where it runs

> **Replaced** by [A library is named by its languages](2026-10-22-a-library-is-named-by-its-languages.md) (2026-10-22): the side is in the namespace, named by its language (clj, cljs, cljc).

> Adds to [A library's name is its door](2026-10-22-a-librarys-name-is-its-door.md).

**Decision:** every `ground-*` library keeps its code under `source-code/`, in three folders named by where the code runs:

- `source-code/server/`: runs only on the server (`.clj`)
- `source-code/both/`: runs on both sides (`.cljc`)
- `source-code/browser/`: runs only in the browser (`.cljs`)

The three are source roots, so the namespace path starts below them and doesn't name the side: `source-code/browser/ground/wire/time.cljs` is `ground.wire.time`. The library's `deps.edn` reads `both` and `server`; its browser build reads `both` and `browser`. The door, `ground.<library>`, sits in whichever of the three it runs in (usually `both`).

**Why:** where a file runs is seen from its folder, before opening it. Keeping the side out of the namespace keeps every name the same however the code is split, and keeps the keywords short.

**The price:** three roots to list in each build, and a file that moves from one side to another moves folders. Clojure tools look for `src` by default, so every build names `source-code` itself. Where tests live is not decided yet.

**Instead of:**
- the side inside the namespace (`ground.wire.server.json`): one root, but the side is in every name that is required
- Clojure's usual single `src`, the file ending telling the side: what most projects do, but the side is seen only from the ending
- other words for the sides: back-end / shared / front-end, back-end / center-end / front-end, jvm / common / js
