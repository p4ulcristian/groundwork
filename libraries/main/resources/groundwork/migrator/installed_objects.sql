-- The objects installed for a part, each with the fingerprint of the
-- statements that made it and of the object as Postgres printed it back.
SELECT installed_object__kind, installed_object__table, installed_object__name,
       installed_object__statement, installed_object__printed
FROM groundwork.installed_objects
WHERE installed_object__part = :installed-object/part
