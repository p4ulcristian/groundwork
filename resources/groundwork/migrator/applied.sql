-- Every migration that already ran, with the fingerprint of its file then.
SELECT migration__source, migration__file, migration__fingerprint
FROM groundwork.migrations
