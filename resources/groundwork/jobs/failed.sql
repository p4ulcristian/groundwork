-- A job that failed: again after :job/retry-ms, or dead when it has had all its attempts.
UPDATE groundwork.jobs
SET job__state      = CASE WHEN job__attempts >= job__max_attempts THEN 'dead' ELSE 'queued' END,
    job__run_at     = now() + :job/retry-ms * interval '1 millisecond',
    job__last_error = :job/last-error,
    job__locked_at  = NULL,
    job__finished_at = CASE WHEN job__attempts >= job__max_attempts THEN now() END
WHERE job__id = CAST(:job/id AS uuid)
RETURNING job__state
