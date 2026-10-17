-- The wall's policies Groundwork put on an app table at an earlier start.
SELECT polname AS policy__name
FROM pg_policy
WHERE polrelid = to_regclass(:table) AND polname LIKE 'groundwork%'
