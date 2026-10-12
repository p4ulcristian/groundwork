# Keys are made once per query, not per row

**Decision:** for each column of a result, the keyword and the way to read its value are chosen once, from the result's header. Every row reuses them.

**Why:** 10 rows or 10,000 cost the same few conversions. Measured in `postgres_test.clj`. Groundwork reads rows with its own small loop, so it never changes how next.jdbc behaves elsewhere in the app.

**Instead of:** converting every key of every row after the query.
