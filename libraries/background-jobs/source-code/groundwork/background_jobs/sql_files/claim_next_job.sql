-- The next due job, for one worker: no other worker, on any server, gets it.
UPDATE groundwork.jobs
SET job__state = 'running', job__locked_at = now(), job__attempts = job__attempts + 1
WHERE job__id = (SELECT job__id FROM groundwork.jobs
                 WHERE job__state = 'queued' AND job__run_at <= now()
                 ORDER BY job__run_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1)
RETURNING job__id, job__kind, job__params::text AS job__params, job__attempts, job__timeout_ms
