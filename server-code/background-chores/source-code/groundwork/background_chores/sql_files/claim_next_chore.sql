-- The next due chore, for one worker: no other worker, on any server, gets it.
UPDATE groundwork.chores
SET chore__state = 'running', chore__locked_at = now(), chore__attempts = chore__attempts + 1
WHERE chore__id = (SELECT chore__id FROM groundwork.chores
                 WHERE chore__state = 'queued' AND chore__run_at <= now()
                 ORDER BY chore__run_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1)
RETURNING chore__id, chore__kind, chore__params, chore__attempts, chore__timeout_ms
