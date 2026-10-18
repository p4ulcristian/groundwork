-- Renames a package.
UPDATE al_packages SET package__name = :package/name WHERE package__id = :package/id
RETURNING package__id, package__name
