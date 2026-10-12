-- Lets the next server migrate.
SELECT pg_advisory_unlock(hashtext('groundwork.migrations')) AS migration_lock__released
