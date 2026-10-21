-- The bells' triggers as the database holds them now, each with a
-- fingerprint of how Postgres prints it back, and whether it is on.
SELECT 'trigger' AS database_object__kind, c.relname AS database_object__table,
       t.tgname AS database_object__name, NULL::text AS database_object__signature,
       md5(format('%s %s', t.tgenabled, pg_get_triggerdef(t.oid))) AS database_object__printed
FROM pg_catalog.pg_trigger t
JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND NOT t.tgisinternal
  AND t.tgname IN ('groundwork_ring_insert', 'groundwork_ring_update', 'groundwork_ring_delete')
