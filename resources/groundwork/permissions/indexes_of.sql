-- The indexes of an app table that can find rows by their first columns:
-- each one's columns in order. An index on an expression, or a partial one,
-- can't be counted on for every row, and is left out.
SELECT ARRAY(SELECT a.attname::text
             FROM unnest(CAST(i.indkey AS int2[])) WITH ORDINALITY AS k(n, o)
             JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.n
             ORDER BY k.o) AS index__columns
FROM pg_index i
WHERE i.indrelid = to_regclass(:table)
  AND i.indpred IS NULL
  AND i.indexprs IS NULL
