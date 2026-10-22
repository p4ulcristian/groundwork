# Functions are small, one job each, in plain Clojure

> Adds to [A name is a sentence that starts with a verb](2026-10-22-a-name-is-a-sentence-that-starts-with-a-verb.md).

**Decision:** a function does one job its name can say, and is short enough to read at a glance: as a rule about ten lines, rarely more than twenty. When a function grows a second job, the job becomes a named function of its own (usually private). The code is plain, idiomatic Clojure:

- data in, data out; the work that touches the world (sending, saving) at the edge, in functions ending in `!`
- a value made by a pure function and handed to the one that sends it (`turn-message-into-sse-text`, then `send-message-on-sse-line!`), not built inside it
- threading (`->`, `cond->`), destructuring and the core sequence functions instead of loops and nested `let`s
- no atom where a value can be made again from what is at hand (a line made from its channel and request each time)
- constant data as a named `def` (`stream-response`, `not-a-websocket`), not written inside a function

**Why:** a small function with a true name is read in one look and tested alone; the name of each piece says what the bigger one does. The owner reads code by what it does, so the pieces' names carry the meaning.

**The price:** more, shorter functions to jump between; a private helper used once.

**Instead of:** long functions with comments marking their parts: the comment says what a named function would, but can't be tested or reused.
