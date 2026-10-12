-- Takes one extra action back.
DELETE FROM groundwork.extra_actions
WHERE extra_action__person_id = :extra-action/person-id
  AND extra_action__scope_id = :extra-action/scope-id
  AND extra_action__action = :extra-action/action
