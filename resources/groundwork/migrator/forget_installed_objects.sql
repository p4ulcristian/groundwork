-- Forgets objects of a part that were dropped (the lists in step).
DELETE FROM groundwork.installed_objects i
USING unnest(:kinds::text[], :tables::text[], :names::text[]) AS o (kind, tbl, name)
WHERE i.installed_object__part = :installed-object/part::text
  AND i.installed_object__kind = o.kind
  AND i.installed_object__table = o.tbl
  AND i.installed_object__name = o.name
