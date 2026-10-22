-- A schedule's first time, unless it has one already.
INSERT INTO groundwork.chore_schedules (chore_schedule__key, chore_schedule__next_at)
VALUES (:chore-schedule/key, :chore-schedule/next-at)
ON CONFLICT (chore_schedule__key) DO NOTHING
