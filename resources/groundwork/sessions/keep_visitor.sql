-- A visitor's session holds something worth keeping: it lives :hours from each visit.
UPDATE groundwork.sessions
SET session__keep_hours = CAST(:hours AS integer),
    session__expires_at = now() + make_interval(hours => CAST(:hours AS integer))
WHERE session__key_hash = :session/key-hash AND session__person_id IS NULL
