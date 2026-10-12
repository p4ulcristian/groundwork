-- A visitor was seen: their session lasts another while.
UPDATE groundwork.sessions
SET session__expires_at = now() + make_interval(hours => CAST(:hours AS integer))
WHERE session__key_hash = :session/key-hash AND session__person_id IS NULL
