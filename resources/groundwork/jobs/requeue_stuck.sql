-- Jobs left running by a server that stopped: queued again, past their time limit and a minute.
UPDATE groundwork.jobs SET job__state = 'queued', job__locked_at = NULL
WHERE job__state = 'running'
  AND job__locked_at < now() - (job__timeout_ms + 60000) * interval '1 millisecond'
