-- Schedules due now, for this server only.
SELECT schedule__key FROM groundwork.job_schedules
WHERE schedule__next_at <= now()
FOR UPDATE SKIP LOCKED
