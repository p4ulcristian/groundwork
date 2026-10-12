-- A schedule's next time.
UPDATE groundwork.job_schedules SET schedule__next_at = :schedule/next-at WHERE schedule__key = :schedule/key
