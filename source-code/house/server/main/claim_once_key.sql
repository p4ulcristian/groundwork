-- Claims a write's one-time key for this message, in the write's own
-- transaction: a row back, claimed now, the write runs; none, claimed
-- before and still kept, the first answer is read. A claim of the same key
-- whose transaction is still running makes this wait until it ends; one
-- rolled back left nothing. A key kept longer than :keep-keys-ms counts as
-- forgotten and is claimed anew.
INSERT INTO house.run_once_keys AS kept (run_once_key__key, run_once_key__message_name, run_once_key__person_id)
VALUES (:run-once-key/key, :run-once-key/message-name, :run-once-key/person-id)
ON CONFLICT (run_once_key__key, run_once_key__message_name) DO UPDATE
SET run_once_key__person_id  = EXCLUDED.run_once_key__person_id,
    run_once_key__answer     = NULL,
    run_once_key__created_at = now()
WHERE kept.run_once_key__created_at < now() - CAST(:keep-keys-ms AS bigint) * interval '1 millisecond'
RETURNING run_once_key__key
