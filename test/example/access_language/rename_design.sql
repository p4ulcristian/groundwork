-- Renames a design.
UPDATE al_designs SET design__name = :design/name WHERE design__id = :design/id RETURNING design__name
