-- A schedule's first time, unless it has one already.
INSERT INTO groundwork.job_schedules (schedule__key, schedule__next_at)
VALUES (:schedule/key, :schedule/next-at)
ON CONFLICT (schedule__key) DO NOTHING
