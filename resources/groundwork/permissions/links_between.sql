-- The foreign keys from a child table to its parent: their columns in order
-- on each side, and whether they carry updates down (ON UPDATE CASCADE).
SELECT ARRAY(SELECT a.attname::text
             FROM unnest(c.conkey) WITH ORDINALITY AS k(n, i)
             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.n
             ORDER BY k.i) AS link__child_columns,
       ARRAY(SELECT a.attname::text
             FROM unnest(c.confkey) WITH ORDINALITY AS k(n, i)
             JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.n
             ORDER BY k.i) AS link__parent_columns,
       c.confupdtype = 'c' AS link__cascades
FROM pg_constraint c
WHERE c.contype = 'f'
  AND c.conrelid = to_regclass(:child)
  AND c.confrelid = to_regclass(:parent)
