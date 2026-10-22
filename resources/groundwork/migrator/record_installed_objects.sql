-- Remembers objects just installed for a part (the lists in step: one
-- object per place).
INSERT INTO groundwork.installed_objects
  (installed_object__part, installed_object__kind, installed_object__table, installed_object__name,
   installed_object__statement, installed_object__printed)
SELECT :installed-object/part::text, o.kind, o.tbl, o.name, o.statement, o.printed
FROM unnest(:kinds::text[], :tables::text[], :names::text[], :statements::text[], :printed::text[])
     AS o (kind, tbl, name, statement, printed)
ON CONFLICT (installed_object__part, installed_object__kind, installed_object__table, installed_object__name)
DO UPDATE SET installed_object__statement = EXCLUDED.installed_object__statement,
              installed_object__printed   = EXCLUDED.installed_object__printed
