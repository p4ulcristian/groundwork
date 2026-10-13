-- The scopes verified for this transaction, the person (or the visitor's
-- session, for someone not signed in), the action and kind
-- of record it runs for, whether it writes, and which of those scopes the
-- person is a member of (there or above), until it ends.
SELECT set_config('groundwork.scopes', :scopes, true) AS wall__scopes,
       set_config('groundwork.person', :person, true) AS wall__person,
       set_config('groundwork.visitor', :visitor, true) AS wall__visitor,
       set_config('groundwork.action', :action, true) AS wall__action,
       set_config('groundwork.kind', :kind, true) AS wall__kind,
       set_config('groundwork.writing', :writing, true) AS wall__writing,
       set_config('groundwork.member_scopes',
                  (WITH RECURSIVE mine(scope) AS (
                     SELECT membership__scope_id FROM groundwork.memberships
                     WHERE membership__person_id = CAST(nullif(:person, '') AS uuid)
                     UNION
                     SELECT s.scope__id FROM groundwork.scopes s JOIN mine m ON s.scope__parent_id = m.scope)
                   SELECT coalesce(CAST(array_agg(scope) AS text), '{}') FROM mine
                   WHERE scope = ANY (CAST(:scopes AS uuid[]))),
                  true) AS wall__member_scopes
