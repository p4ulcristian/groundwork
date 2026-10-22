-- Chores in a state, newest first.
SELECT chore__id, chore__kind, chore__params, chore__state, chore__run_at, chore__attempts,
       chore__max_attempts, chore__last_error, chore__created_at
FROM groundwork.chores WHERE chore__state = :chore/state
ORDER BY chore__created_at DESC LIMIT :limit
