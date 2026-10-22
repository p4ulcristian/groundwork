-- A schedule's next time.
UPDATE groundwork.chore_schedules SET chore_schedule__next_at = :chore-schedule/next-at
WHERE chore_schedule__key = :chore-schedule/key
