-- Jobs in a state, newest first, for the REPL.
SELECT job__id, job__kind, job__params::text AS job__params, job__state, job__run_at, job__attempts,
       job__max_attempts, job__last_error, job__created_at
FROM groundwork.jobs WHERE job__state = :job/state
ORDER BY job__created_at DESC LIMIT :limit
