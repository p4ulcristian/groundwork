-- What a role lacks of what migrate grants the app's role (grants.sql):
-- USAGE on the schemas public and groundwork; SELECT, INSERT, UPDATE and
-- DELETE on every table (only SELECT on Groundwork's records of migrations
-- and installs), SELECT on every view; USAGE, SELECT and UPDATE on every
-- sequence; EXECUTE on every function. An extension's objects are left out.
SELECT what AS missing_grant__what
FROM (
  SELECT format('USAGE on schema %I', n.nspname) AS what
  FROM pg_catalog.pg_namespace n
  WHERE n.nspname IN ('public', 'groundwork')
    AND NOT has_schema_privilege(:role/name::name, n.oid, 'USAGE')
  UNION ALL
  SELECT format('%s on %I.%I', p.privilege, n.nspname, c.relname)
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  CROSS JOIN unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE']) AS p (privilege)
  WHERE n.nspname IN ('public', 'groundwork')
    AND (c.relkind IN ('r', 'p') OR (c.relkind IN ('v', 'm') AND p.privilege = 'SELECT'))
    AND NOT (n.nspname = 'groundwork' AND c.relname IN ('migrations', 'installs') AND p.privilege <> 'SELECT')
    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                    WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
    AND NOT has_table_privilege(:role/name::name, c.oid, p.privilege)
  UNION ALL
  SELECT format('%s on sequence %I.%I', p.privilege, n.nspname, c.relname)
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  CROSS JOIN unnest(ARRAY['USAGE', 'SELECT', 'UPDATE']) AS p (privilege)
  WHERE n.nspname IN ('public', 'groundwork')
    AND c.relkind = 'S'
    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                    WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
    AND NOT has_sequence_privilege(:role/name::name, c.oid, p.privilege)
  UNION ALL
  SELECT format('EXECUTE on function %s', p.oid::regprocedure)
  FROM pg_catalog.pg_proc p
  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
  WHERE n.nspname IN ('public', 'groundwork')
    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                    WHERE d.classid = 'pg_catalog.pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
    AND NOT has_function_privilege(:role/name::name, p.oid, 'EXECUTE')
) missing
ORDER BY 1
