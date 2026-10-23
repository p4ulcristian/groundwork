-- The one-time keys kept longer than :keep-keys-ms, forgotten; a repeat
-- that late runs again (the claim treats such a key as forgotten already).
DELETE FROM groundwork.run_once_keys
WHERE run_once_key__created_at < now() - CAST(:keep-keys-ms AS bigint) * interval '1 millisecond'
