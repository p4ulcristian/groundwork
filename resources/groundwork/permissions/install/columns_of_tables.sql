-- The columns of app tables, to check a permission description against,
-- with their types (a via's function returns the columns it matches): each
-- table as it was named, a table that doesn't exist with no rows.
SELECT t.name AS column__table, a.attname AS column__name, a.attnotnull AS column__not_null,
       format_type(a.atttypid, a.atttypmod) AS column__type
FROM unnest(:tables::text[]) AS t (name)
JOIN pg_attribute a ON a.attrelid = to_regclass(t.name)
WHERE a.attnum > 0
  AND NOT a.attisdropped
