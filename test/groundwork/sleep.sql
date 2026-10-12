-- Waits, for testing the statement timeout.
SELECT pg_sleep(:seconds) IS NULL AS slept__done
