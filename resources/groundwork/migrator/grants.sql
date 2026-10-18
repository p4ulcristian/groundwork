-- The statements that grant the app's role what it needs and no more of
-- Groundwork's records (missing_grants.sql checks them), in order. Run by
-- migrate after every migration and install, so what a migration just made
-- is granted too.
SELECT statement AS grant__statement
FROM (VALUES
  (1, format('GRANT USAGE ON SCHEMA public, groundwork TO %I', :role/name::text)),
  (2, format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public, groundwork TO %I', :role/name::text)),
  (3, format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON groundwork.migrations, groundwork.installs FROM %I', :role/name::text)),
  (4, format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public, groundwork TO %I', :role/name::text)),
  (5, format('GRANT EXECUTE ON ALL ROUTINES IN SCHEMA public, groundwork TO %I', :role/name::text))
) AS g (n, statement)
ORDER BY n
