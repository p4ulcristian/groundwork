-- Done chores are kept a week, then forgotten.
DELETE FROM groundwork.chores WHERE chore__state = 'done' AND chore__finished_at < now() - interval '7 days'
