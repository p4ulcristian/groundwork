-- The answer a write gave, as JSON text, kept for a repeat of its key.
UPDATE house.run_once_keys
SET run_once_key__answer = :run-once-key/answer
WHERE run_once_key__key = :run-once-key/key
  AND run_once_key__message_name = :run-once-key/message-name
