-- A chore that failed: queued again after :chore/retry-ms, or dead when it has
-- had all its attempts or gave up for good.
UPDATE groundwork.chores
SET chore__state       = CASE WHEN CAST(:chore/for-good AS boolean) OR chore__attempts >= chore__max_attempts THEN 'dead' ELSE 'queued' END,
    chore__run_at      = now() + :chore/retry-ms * interval '1 millisecond',
    chore__last_error  = :chore/last-error,
    chore__locked_at   = NULL,
    chore__finished_at = CASE WHEN CAST(:chore/for-good AS boolean) OR chore__attempts >= chore__max_attempts THEN now() END
WHERE chore__id = :chore/id
RETURNING chore__state
