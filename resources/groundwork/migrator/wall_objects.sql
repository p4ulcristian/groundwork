-- The wall's objects as the database holds them now, each with a
-- fingerprint of how Postgres prints it back: row-level security on the
-- app's tables (the ones that have it on), Groundwork's policies,
-- Groundwork's triggers on the app's tables but the bells' (the guards and
-- fill rules), and the functions Groundwork makes from the settings (named
-- via__, via_guard__, may, denied scopes, fill_scope__, carry_person__). A
-- function's signature is how it is dropped.
SELECT 'row-security' AS database_object__kind, c.relname AS database_object__table,
       'forced' AS database_object__name, NULL::text AS database_object__signature,
       md5(format('%s %s', c.relrowsecurity, c.relforcerowsecurity)) AS database_object__printed
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
  AND (c.relrowsecurity OR c.relforcerowsecurity)
UNION ALL
SELECT 'policy', c.relname, p.polname, NULL,
       md5(format('%s %s %s using %s check %s', p.polcmd, p.polpermissive, p.polroles::text,
                  pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid)))
FROM pg_catalog.pg_policy p
JOIN pg_catalog.pg_class c ON c.oid = p.polrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND p.polname LIKE 'groundwork%'
UNION ALL
SELECT 'trigger', c.relname, t.tgname, NULL,
       md5(format('%s %s', t.tgenabled, pg_get_triggerdef(t.oid)))
FROM pg_catalog.pg_trigger t
JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND NOT t.tgisinternal
  AND t.tgname LIKE 'groundwork%'
  AND t.tgname NOT IN ('groundwork_ring_insert', 'groundwork_ring_update', 'groundwork_ring_delete')
UNION ALL
SELECT 'function', '', p.proname, p.oid::regprocedure::text, md5(pg_get_functiondef(p.oid))
FROM pg_catalog.pg_proc p
JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'groundwork' AND p.prokind = 'f'
  AND (p.proname LIKE 'via\_\_%' OR p.proname LIKE 'via\_guard\_\_%' OR p.proname LIKE 'may %'
       OR p.proname LIKE 'denied scopes %' OR p.proname LIKE 'fill\_scope\_\_%'
       OR p.proname LIKE 'carry\_person\_\_%')
