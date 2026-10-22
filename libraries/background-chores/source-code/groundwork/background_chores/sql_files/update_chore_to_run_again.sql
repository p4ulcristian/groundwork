-- A dead or waiting chore, queued to run now with its attempts counted afresh.
UPDATE groundwork.chores
SET chore__state = 'queued', chore__run_at = now(), chore__attempts = 0, chore__finished_at = NULL
WHERE chore__id = :chore/id AND chore__state IN ('dead', 'queued')
RETURNING chore__id
