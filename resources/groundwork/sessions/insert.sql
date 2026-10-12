-- A new session for a person, ending after its lifetime.
INSERT INTO groundwork.sessions (session__key_hash, session__person_id, session__data, session__expires_at)
VALUES (:session/key-hash, :session/person-id, :session/data,
        now() + make_interval(days => CAST(:max-age-days AS integer)))
RETURNING session__id
