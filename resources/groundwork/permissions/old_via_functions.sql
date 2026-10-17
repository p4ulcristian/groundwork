-- A via's functions of two arguments (person, visitor), from before a via's
-- related row was held to the request's scopes.
SELECT p.proname AS function__name
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'groundwork' AND p.proname LIKE 'via\_\_%' AND p.pronargs = 2
