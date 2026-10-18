-- Groundwork's generated functions from an earlier start: a list's
-- narrowing by an action, a scope state's deny. Each is named as it reads.
SELECT p.proname AS function__name
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'groundwork' AND (p.proname LIKE 'may %' OR p.proname LIKE 'denied scopes %')
