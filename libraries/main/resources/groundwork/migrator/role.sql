-- A role by name, and whether it passes by row-level security (a superuser,
-- or a role with BYPASSRLS); no row when there is no such role.
SELECT rolname AS role__name, (rolsuper OR rolbypassrls) AS role__bypasses_rls
FROM pg_catalog.pg_roles
WHERE rolname = :role/name
