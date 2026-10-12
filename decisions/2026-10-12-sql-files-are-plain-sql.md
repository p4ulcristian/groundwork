# SQL files are plain SQL

**Decision:** columns are written as they are, `post__title`. Only params use a colon: `:post/title`, `:limit`.

**Why:** a column in the file is the real column, with nothing rewritten. Editors and SQL tools understand the files. The parser only handles params.

**Instead of:** writing columns as `:post/title` and rewriting them. The files read like Clojure, but they weren't SQL anymore.

Trying a query happens in the REPL, see `2026-10-12-we-try-queries-in-the-repl.md`.
