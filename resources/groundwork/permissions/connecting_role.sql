-- The role the app connects as, and whether it passes by row-level security
-- (a superuser, or a role with BYPASSRLS): then no policy applies to it.
SELECT rolname AS role__name, (rolsuper OR rolbypassrls) AS role__bypasses_rls
  FROM pg_catalog.pg_roles
 WHERE rolname = current_user
