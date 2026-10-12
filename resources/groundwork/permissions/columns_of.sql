-- The columns of an app table, to check a permission description against.
SELECT a.attname AS column__name, a.attnotnull AS column__not_null
FROM pg_attribute a
WHERE a.attrelid = to_regclass(:table)
  AND a.attnum > 0
  AND NOT a.attisdropped
