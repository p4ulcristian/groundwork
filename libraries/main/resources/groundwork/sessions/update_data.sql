-- What the app keeps in a session.
UPDATE groundwork.sessions SET session__data = :session/data
WHERE session__key_hash = :session/key-hash
