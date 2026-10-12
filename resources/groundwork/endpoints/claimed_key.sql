-- A key claimed before: who claimed it, for which endpoint, and its answer.
SELECT run_once__person_id, run_once__endpoint, run_once__answer
FROM groundwork.run_once
WHERE run_once__key = :run-once/key
