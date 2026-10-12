-- A job, due at :job/run-at; workers hear about it when the transaction commits.
-- With a :job/key, a job of that key still waiting or running is kept instead.
WITH job AS (
  INSERT INTO groundwork.jobs (job__kind, job__params, job__run_at, job__max_attempts, job__timeout_ms, job__key)
  VALUES (:job/kind, CAST(:job/params AS jsonb), :job/run-at, :job/max-attempts, :job/timeout-ms, :job/key)
  ON CONFLICT (job__key) WHERE job__key IS NOT NULL AND job__state IN ('queued', 'running') DO NOTHING
  RETURNING job__id
)
SELECT job__id, pg_notify('groundwork_jobs', '') IS NOT NULL AS job__notified FROM job
UNION ALL
SELECT job__id, false AS job__notified FROM groundwork.jobs
WHERE job__key = :job/key AND job__state IN ('queued', 'running') AND NOT EXISTS (SELECT 1 FROM job)
