-- Remembers that a migration ran.
INSERT INTO groundwork.migrations (migration__source, migration__file, migration__fingerprint)
VALUES (:migration/source, :migration/file, :migration/fingerprint)
