-- A schedule's next time.
UPDATE groundwork.job_schedules SET job_schedule__next_at = :job-schedule/next-at WHERE job_schedule__key = :job-schedule/key
