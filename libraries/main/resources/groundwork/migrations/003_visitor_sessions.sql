-- A visitor (someone not signed in) can have a session too: no person yet.
ALTER TABLE groundwork.sessions ALTER COLUMN session__person_id DROP NOT NULL;
