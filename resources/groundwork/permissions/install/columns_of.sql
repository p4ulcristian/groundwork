-- The columns of an app table, to check a permission description against,
-- with their types (a via's function returns the columns it matches).
SELECT a.attname AS column__name, a.attnotnull AS column__not_null,
       format_type(a.atttypid, a.atttypmod) AS column__type
FROM pg_attribute a
WHERE a.attrelid = to_regclass(:table)
  AND a.attnum > 0
  AND NOT a.attisdropped
