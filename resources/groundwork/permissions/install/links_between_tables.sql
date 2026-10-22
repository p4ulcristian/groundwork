-- The foreign keys from child tables to their parents, for each pair named
-- (children and parents in step): the pair as it was named, the key's
-- columns in order on each side, and whether it carries updates down (ON
-- UPDATE CASCADE).
SELECT pair.child AS link__child, pair.parent AS link__parent,
       ARRAY(SELECT a.attname::text
             FROM unnest(c.conkey) WITH ORDINALITY AS k(n, i)
             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.n
             ORDER BY k.i) AS link__child_columns,
       ARRAY(SELECT a.attname::text
             FROM unnest(c.confkey) WITH ORDINALITY AS k(n, i)
             JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.n
             ORDER BY k.i) AS link__parent_columns,
       c.confupdtype = 'c' AS link__cascades
FROM unnest(:children::text[], :parents::text[]) AS pair (child, parent)
JOIN pg_constraint c ON c.conrelid = to_regclass(pair.child) AND c.confrelid = to_regclass(pair.parent)
WHERE c.contype = 'f'
