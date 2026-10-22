-- A dead (or waiting) job, run again now with its attempts counted afresh.
UPDATE groundwork.jobs
SET job__state = 'queued', job__run_at = now(), job__attempts = 0, job__finished_at = NULL
WHERE job__id = CAST(:job/id AS uuid) AND job__state IN ('dead', 'queued')
RETURNING job__id, pg_notify('groundwork_jobs', '') IS NOT NULL AS job__notified
