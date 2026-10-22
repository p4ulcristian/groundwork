-- Waits until no other server is migrating this database.
SELECT pg_advisory_lock(hashtext('groundwork.migrations')) IS NULL AS migration_lock__taken
