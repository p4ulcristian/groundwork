-- The wall by action: besides the verified scopes, a transaction carries the
-- action it runs for and the verified scopes the person is a member of (in
-- the scope or one above it). Rows of a kind the action has no rule for are
-- visible only in those, so what a link gives is the linked action's records
-- and nothing else there. All of it ends with the transaction.
CREATE FUNCTION groundwork.wall_member(scope uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('groundwork.bypass', true), '') = 'on'
      OR scope = ANY (coalesce(nullif(current_setting('groundwork.member_scopes', true), ''), '{}')::uuid[])
$$;
