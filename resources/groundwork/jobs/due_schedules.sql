-- Schedules due now, for this server only.
SELECT job_schedule__key FROM groundwork.job_schedules
WHERE job_schedule__next_at <= now()
FOR UPDATE SKIP LOCKED
