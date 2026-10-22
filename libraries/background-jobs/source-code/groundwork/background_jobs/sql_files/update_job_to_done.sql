-- A job that ran.
UPDATE groundwork.jobs SET job__state = 'done', job__finished_at = now(), job__locked_at = NULL
WHERE job__id = :job/id
