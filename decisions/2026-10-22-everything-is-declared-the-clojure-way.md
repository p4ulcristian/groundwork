# Everything is declared, the Clojure way

> **Changed in part** by [A table is one map](2026-10-22-a-table-is-one-map.md) (2026-10-22): the tables case is decided.

> Adds to [Declared, through one door](2026-10-14-declared-through-one-door.md).

**Decision:** everything an app tells Groundwork is a declaration of what is, not steps to get there, written the way Clojure writes things: plain data (maps, vectors, namespaced keywords) that Groundwork reads, and functions only where behaviour itself is the point (a handler, a job's work). Where the thing is SQL or CSS, the declaration is a plain file of that language, as today.

- An app says the result it wants; Groundwork works out how to get from what is there to it.
- A declaration is data Groundwork can read, so one declaration feeds every part that needs it (the access sentences already make the wall, the check, "can I?", the bells and the leak test).
- No macros or language of Groundwork's own where a map does the job.
- Where an app still writes steps today, Groundwork looks for the declaration that replaces them. The first one under design: tables declared by their shape, with change files only for what a shape can't say (a rename, a drop, a backfill). It is not decided yet.

**Why:** a declaration shows the whole of a thing in one place, for the person deciding and for the AI writing the code. Groundwork can read data and derive the rest from it, so a fact is said once. It is how Clojure already works: data first, functions on data.

**The price:** Groundwork carries the work of converging: comparing what is with what is declared, and refusing what it can't do safely. That engine is harder to build than running steps. Some things stay steps, because data that moves can't be declared.

**Instead of:**
- steps the app writes and Groundwork runs (numbered migrations are steps: the shape is only the sum of every file)
- a language or macros of Groundwork's own: data needs no new grammar, and any Clojure function can read and build it
