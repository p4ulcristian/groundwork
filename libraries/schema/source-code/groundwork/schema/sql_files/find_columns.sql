-- Every column of the app's tables, in their order.
SELECT c.relname::text                        AS table_name,
       a.attname::text                        AS column_name,
       format_type(a.atttypid, a.atttypmod)   AS column_type,
       a.attnotnull                           AS not_null,
       pg_get_expr(d.adbin, d.adrelid)        AS default_text
FROM pg_attribute a
JOIN pg_class c     ON c.oid = a.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped
ORDER BY c.relname, a.attnum
