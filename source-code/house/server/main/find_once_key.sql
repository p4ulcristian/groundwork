-- A key claimed before for this message: who claimed it, and the answer
-- its write gave (NULL when its steps gave none).
SELECT run_once_key__person_id, run_once_key__answer
FROM house.run_once_keys
WHERE run_once_key__key = :run-once-key/key
  AND run_once_key__message_name = :run-once-key/message-name
