# row throws on more than one row

**Decision:** `pg/row` returns the one row or `nil`. A second row throws `:groundwork.postgres/more-than-one-row`, naming the file. Postgres sends at most two rows for it, so a runaway query isn't read in full first.

**Why:** a second row means something is wrong: a missing `WHERE`, duplicate data, a join that multiplies rows. Without `ORDER BY` the "first" row can differ on every call, so taking it would hide the bug behind an answer that looks right. When the newest one is what's meant, the SQL says so: `ORDER BY … LIMIT 1`.

**Instead of:** taking the first row. It never fails, but a wrong answer looks like a right one.
