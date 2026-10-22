-- The indexes of app tables that can find rows by their first columns: each
-- table as it was named, each index's columns in order, and a partial one's
-- predicate as Postgres prints it (nil for a whole one). An index on an
-- expression is left out.
SELECT t.name AS index__table,
       ARRAY(SELECT a.attname::text
             FROM unnest(CAST(i.indkey AS int2[])) WITH ORDINALITY AS k(n, o)
             JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.n
             ORDER BY k.o) AS index__columns,
       pg_get_expr(i.indpred, i.indrelid) AS index__predicate
FROM unnest(:tables::text[]) AS t (name)
JOIN pg_index i ON i.indrelid = to_regclass(t.name)
WHERE i.indexprs IS NULL
