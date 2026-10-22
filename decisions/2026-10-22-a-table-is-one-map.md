# A table is one map

> Decides the case [Everything is declared, the Clojure way](2026-10-22-everything-is-declared-the-clojure-way.md) left open. Changes [Migrations are numbered SQL files](2026-10-12-migrations-are-numbered-sql-files.md), [SQL lives only in files](2026-10-12-sql-lives-only-in-files.md), [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md) and [Asking again, and bells that miss nothing](2026-10-14-asking-again-and-bells-that-miss-nothing.md). Brings back what [Migrations are numbered SQL files](2026-10-12-migrations-are-numbered-sql-files.md) chose against: the database is brought to a declared shape.

**Decision:** each of an app's tables is declared as one Clojure map, next to its area: its shape (columns, keys, references, defaults, indexes), who may do what on its rows (its access sentences), and how it rings. Groundwork brings the database to what the maps say and makes the rest from them. What a map can't say is a change file.

- **The map is the truth.** Adding a table, a column or an index is editing a map; Groundwork compares the maps with the database and makes what is missing.
- **Whose a row is comes from its references.** A column that references a scope's table names the row's scope; one that references the person names its person. A record kind's table, id, whose columns and parent are read from the map, not declared again.
- **The access sentences of a table sit on its map.** The language stays as decided; only where a sentence is written moves.
- **How a table rings sits on its map** (by who may see, global, personal, or silent with its reason).
- **Groundwork makes from the map:** the table's statements, the wall, the bells' triggers, and the malli shape of its rows.
- **Change files, also data, for what a shape can't say:** a rename, a drop (with why), a backfill (naming a `.sql` file). Each runs once, in order.
- **Nothing is dropped or renamed by guessing.** A column or table in the database that no map has stops migrate until a change file says what happens to it.
- **What maps don't cover stays SQL,** compared object by object like the wall (views, functions of the app's own).

**Why:** the Clojure way is the best way for Groundwork: data first, functions on data. A table is then read in one place, by the person deciding and by the AI writing the code: what it holds, who sees it, who hears about it. Facts said twice today are said once: a table's shape (in migrations, its records entry and endpoint answers), its scope (its column and `:whose`). Groundwork already brings its own objects to what the settings make, object by object; this extends it to the app's tables. The reasons this was turned down on 2026-10-12 are gone: there is no outside tool, and nothing is dropped without a change file.

**The price:**
- Comparing tables is the hardest engine in Groundwork: types, defaults, constraints, indexes, and the order of changes (a rename before comparing, a backfill between adding a column and making it NOT NULL).
- A big table needs its index built without blocking writes.
- Both running apps move from numbered migrations to maps in one change.
- A table's statements are made from data, not written in a `.sql` file; queries stay in `.sql` files.

**Instead of:**
- SQL shape files: plain Postgres, but a table's access lives in another file, and Groundwork would have to read SQL to compare
- malli schemas as tables: one schema language with the endpoints, but keys, indexes and defaults all become properties
- an outside tool (Atlas, pg-schema-diff): its engine for free, but another binary and format, and the wall stays Groundwork's anyway
- keeping numbered migrations: nothing to build, but the shape stays the sum of every file
