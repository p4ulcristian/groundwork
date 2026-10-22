-- A key claimed before: who claimed it, for which endpoint, and its answer.
SELECT run_once_key__person_id, run_once_key__endpoint, run_once_key__answer
FROM groundwork.run_once_keys
WHERE run_once_key__key = :run-once-key/key
