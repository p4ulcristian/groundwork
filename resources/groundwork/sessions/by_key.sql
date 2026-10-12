-- The live session a cookie's key belongs to.
SELECT session__id, session__person_id, session__data, session__expires_at, session__keep_hours
FROM groundwork.sessions
WHERE session__key_hash = :session/key-hash
  AND session__expires_at > now()
