# SQL lives only in files

**Decision:** every statement is a `.sql` file next to the namespace that uses it, named by a var: `(pg/defsql list-recent)`. No SQL built from maps, no inline SQL strings.

**Why:**
- one way to write a query, so no choosing
- every statement can be found, reviewed and linted
- `defsql` makes a query a real var: a typo is a compile error, a missing file fails when the namespace loads, the file's first comment is the docstring

**Instead of:** `insert!`/`update!`/`delete!` from maps, and `(pg/sql "…")`. Their only gain was shorter inserts.
