-- The writes a role holds itself on Groundwork's own records (of
-- migrations and installs), which the app's role may only read: none,
-- after a migrate. Each with the statement that takes it back.
SELECT format('%s on groundwork.%I', a.privilege_type, c.relname) AS extra_grant__what,
       format('REVOKE %s ON groundwork.%I FROM %I', a.privilege_type, c.relname, :role/name::text) AS extra_grant__statement
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN LATERAL aclexplode(c.relacl) AS a
JOIN pg_catalog.pg_roles r ON r.oid = a.grantee
WHERE n.nspname = 'groundwork' AND c.relname IN ('migrations', 'installs', 'installed_objects')
  AND r.rolname = :role/name
  AND a.privilege_type IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
ORDER BY 1
