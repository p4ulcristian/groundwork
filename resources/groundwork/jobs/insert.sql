-- A job, due at :job/run-at; workers hear about it when the transaction commits.
WITH job AS (
  INSERT INTO groundwork.jobs (job__kind, job__params, job__run_at, job__max_attempts, job__timeout_ms)
  VALUES (:job/kind, CAST(:job/params AS jsonb), :job/run-at, :job/max-attempts, :job/timeout-ms)
  RETURNING job__id
)
SELECT job__id, pg_notify('groundwork_jobs', '') IS NOT NULL AS job__notified FROM job
