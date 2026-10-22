-- Primary keys, foreign keys and checks of the app's tables.
SELECT c.relname::text            AS table_name,
       con.conname::text          AS constraint_name,
       con.contype::text          AS constraint_type,
       con.convalidated           AS validated,
       pg_get_constraintdef(con.oid) AS definition,
       (SELECT array_agg(a.attname::text ORDER BY k.ord)
        FROM unnest(con.conkey) WITH ORDINALITY k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) AS column_names,
       fc.relname::text           AS foreign_table,
       (SELECT array_agg(a.attname::text ORDER BY k.ord)
        FROM unnest(con.confkey) WITH ORDINALITY k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum) AS foreign_column_names
FROM pg_constraint con
JOIN pg_class c     ON c.oid = con.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_class fc ON fc.oid = con.confrelid
WHERE n.nspname = 'public'
