-- Done jobs are kept a week, then forgotten.
DELETE FROM groundwork.jobs WHERE job__state = 'done' AND job__finished_at < now() - interval '7 days'
