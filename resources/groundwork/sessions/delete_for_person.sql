-- Signs a person out everywhere.
DELETE FROM groundwork.sessions WHERE session__person_id = :session/person-id
