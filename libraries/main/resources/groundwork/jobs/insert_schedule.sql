-- A schedule's first time, unless it has one already.
INSERT INTO groundwork.job_schedules (job_schedule__key, job_schedule__next_at)
VALUES (:job-schedule/key, :job-schedule/next-at)
ON CONFLICT (job_schedule__key) DO NOTHING
