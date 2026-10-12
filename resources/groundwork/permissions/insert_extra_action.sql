-- One action on top of a person's role.
INSERT INTO groundwork.extra_actions (extra_action__person_id, extra_action__scope_id, extra_action__action)
VALUES (:extra-action/person-id, :extra-action/scope-id, :extra-action/action)
ON CONFLICT DO NOTHING
