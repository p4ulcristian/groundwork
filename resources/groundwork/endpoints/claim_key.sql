-- Claims a write's one-time key. No row back when it was claimed before;
-- a claim still running makes this wait until it ends.
INSERT INTO groundwork.run_once_keys (run_once_key__key, run_once_key__person_id, run_once_key__endpoint)
VALUES (:run-once-key/key, :run-once-key/person-id, :run-once-key/endpoint)
ON CONFLICT (run_once_key__key) DO NOTHING
RETURNING run_once_key__key
