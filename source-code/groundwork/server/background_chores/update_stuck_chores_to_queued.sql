-- Chores left running by a server that stopped: queued again, once past their
-- time limit and a minute.
UPDATE groundwork.chores SET chore__state = 'queued', chore__locked_at = NULL
WHERE chore__state = 'running'
  AND chore__locked_at < now() - (chore__timeout_ms + 60000) * interval '1 millisecond'
