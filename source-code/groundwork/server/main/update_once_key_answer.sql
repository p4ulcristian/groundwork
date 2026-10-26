-- The answer a write gave, as JSON text, kept for a repeat of its key.
UPDATE groundwork.run_once_keys
SET run_once_key__answer = param('run-once-key/answer')
WHERE run_once_key__key = param('run-once-key/key')
  AND run_once_key__message_name = param('run-once-key/message-name')
