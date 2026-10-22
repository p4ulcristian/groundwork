-- A dead or waiting job, queued to run now with its attempts counted afresh.
UPDATE groundwork.jobs
SET job__state = 'queued', job__run_at = now(), job__attempts = 0, job__finished_at = NULL
WHERE job__id = :job/id AND job__state IN ('dead', 'queued')
RETURNING job__id
