-- The guards Groundwork put on app tables at earlier starts: a via's (and,
-- before the access language, a pass's).
SELECT c.relname AS guard__table, t.tgname AS guard__name
FROM pg_catalog.pg_trigger t
JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND NOT t.tgisinternal
  AND (t.tgname = 'groundwork_via_guard' OR t.tgname LIKE 'groundwork\_pass\_guard\_\_%')
