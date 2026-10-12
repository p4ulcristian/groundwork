-- One tick in a scope's roles grid.
INSERT INTO groundwork.role_actions (role_action__role_id, role_action__action)
VALUES (:role-action/role-id, :role-action/action)
ON CONFLICT DO NOTHING
