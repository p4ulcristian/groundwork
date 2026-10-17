-- A transaction behind the wall: the scopes verified for its reads ('*' for
-- every scope), the scopes it may write in, the person (or the visitor's
-- session, for someone not signed in) and the session's flags, until it
-- ends. See groundwork.permissions.rules for the wall these feed.
SELECT set_config('groundwork.scopes', :scopes, true) AS wall__scopes,
       set_config('groundwork.write_scopes', :write-scopes, true) AS wall__write_scopes,
       set_config('groundwork.person', :person, true) AS wall__person,
       set_config('groundwork.visitor', :visitor, true) AS wall__visitor,
       set_config('groundwork.flags', :flags, true) AS wall__flags
