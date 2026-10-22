-- Schedules due now, locked for this server only.
SELECT chore_schedule__key FROM groundwork.chore_schedules
WHERE chore_schedule__next_at <= now()
FOR UPDATE SKIP LOCKED
