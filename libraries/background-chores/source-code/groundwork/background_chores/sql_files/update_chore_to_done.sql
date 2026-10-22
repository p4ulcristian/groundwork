-- A chore that ran.
UPDATE groundwork.chores SET chore__state = 'done', chore__finished_at = now(), chore__locked_at = NULL
WHERE chore__id = :chore/id
