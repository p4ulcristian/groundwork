-- The indexes of an app table that can find rows by their first columns:
-- each one's columns in order, and a partial one's predicate as Postgres
-- prints it (nil for a whole one). An index on an expression is left out.
SELECT ARRAY(SELECT a.attname::text
             FROM unnest(CAST(i.indkey AS int2[])) WITH ORDINALITY AS k(n, o)
             JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.n
             ORDER BY k.o) AS index__columns,
       pg_get_expr(i.indpred, i.indrelid) AS index__predicate
FROM pg_index i
WHERE i.indrelid = to_regclass(:table)
  AND i.indexprs IS NULL
