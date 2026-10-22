-- A person's live sessions, for the REPL: when each began and ends, and its flags.
SELECT left(encode(session__key_hash, 'hex'), 12) AS session__hash, session__created_at, session__expires_at,
       session__data -> 'session/flags' AS session__flags
FROM groundwork.sessions
WHERE session__person_id = :session/person-id AND session__expires_at > now()
ORDER BY session__created_at
