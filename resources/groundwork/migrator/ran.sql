-- Every migration that already ran, its fingerprint then, and when.
SELECT migration__source, migration__file, migration__fingerprint, migration__ran_at
FROM groundwork.migrations
ORDER BY migration__ran_at, migration__file
