-- What a role owns of the app's and Groundwork's objects (the schemas public
-- and groundwork, their tables, views, sequences and functions), itself or
-- through a role it is a member of. A role owning one could switch the wall
-- off or drop a table. An extension's objects are left out.
SELECT what AS owned__what
FROM (
  SELECT format('schema %I', n.nspname) AS what
  FROM pg_catalog.pg_namespace n
  WHERE n.nspname IN ('public', 'groundwork')
    AND pg_has_role(:role/name::name, n.nspowner, 'MEMBER')
  UNION ALL
  SELECT format('%s %I.%I',
                CASE c.relkind WHEN 'S' THEN 'sequence' WHEN 'v' THEN 'view'
                               WHEN 'm' THEN 'materialized view' WHEN 'f' THEN 'foreign table'
                               ELSE 'table' END,
                n.nspname, c.relname)
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname IN ('public', 'groundwork')
    AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                    WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
    AND pg_has_role(:role/name::name, c.relowner, 'MEMBER')
  UNION ALL
  SELECT format('function %s', p.oid::regprocedure)
  FROM pg_catalog.pg_proc p
  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
  WHERE n.nspname IN ('public', 'groundwork')
    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                    WHERE d.classid = 'pg_catalog.pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
    AND pg_has_role(:role/name::name, p.proowner, 'MEMBER')
) owned
ORDER BY 1
