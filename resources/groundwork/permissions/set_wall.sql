-- The scopes verified for this transaction, and the person it runs for, until it ends.
SELECT set_config('groundwork.scopes', :scopes, true) AS wall__scopes,
       set_config('groundwork.person', :person, true) AS wall__person
