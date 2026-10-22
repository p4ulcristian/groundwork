-- Signs one session out.
DELETE FROM groundwork.sessions WHERE session__key_hash = :session/key-hash
