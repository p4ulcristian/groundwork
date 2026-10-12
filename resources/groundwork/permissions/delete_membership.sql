-- Takes a person out of a scope, with their extra actions there.
WITH gone AS (
  DELETE FROM groundwork.extra_actions
  WHERE extra_action__person_id = :membership/person-id AND extra_action__scope_id = :membership/scope-id
)
DELETE FROM groundwork.memberships
WHERE membership__person_id = :membership/person-id AND membership__scope_id = :membership/scope-id
