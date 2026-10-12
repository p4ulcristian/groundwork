-- A new session, ending after its lifetime. A visitor's has no person.
INSERT INTO groundwork.sessions (session__key_hash, session__person_id, session__data, session__expires_at)
VALUES (:session/key-hash, :session/person-id, :session/data,
        now() + make_interval(hours => CAST(:hours AS integer)))
RETURNING session__id
