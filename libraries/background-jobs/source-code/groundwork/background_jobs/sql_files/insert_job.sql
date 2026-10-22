-- A job, due at :job/run-at. With a :job/key, a job of that key that is
-- still remembered is kept instead, and its id returned.
WITH job AS (
  INSERT INTO groundwork.jobs (job__kind, job__params, job__run_at, job__max_attempts, job__timeout_ms, job__key)
  VALUES (:job/kind, CAST(:job/params AS jsonb), :job/run-at, :job/max-attempts, :job/timeout-ms, :job/key)
  ON CONFLICT (job__key) WHERE job__key IS NOT NULL DO NOTHING
  RETURNING job__id
)
SELECT job__id FROM job
UNION ALL
SELECT job__id FROM groundwork.jobs
WHERE job__key = :job/key AND NOT EXISTS (SELECT 1 FROM job)
