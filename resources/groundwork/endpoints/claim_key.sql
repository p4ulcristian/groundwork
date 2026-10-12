-- Claims a write's one-time key. No row back when it was claimed before;
-- a claim still running makes this wait until it ends.
INSERT INTO groundwork.run_once (run_once__key, run_once__person_id, run_once__endpoint)
VALUES (:run-once/key, :run-once/person-id, :run-once/endpoint)
ON CONFLICT (run_once__key) DO NOTHING
RETURNING run_once__key
