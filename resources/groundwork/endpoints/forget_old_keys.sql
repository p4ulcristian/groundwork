-- Forgets one-time keys older than a day: a repeat comes within seconds.
DELETE FROM groundwork.run_once WHERE run_once__created_at < now() - interval '1 day'
