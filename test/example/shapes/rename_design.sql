-- Renames a design.
UPDATE sh_designs SET design__name = :design/name WHERE design__id = :design/id RETURNING design__name
