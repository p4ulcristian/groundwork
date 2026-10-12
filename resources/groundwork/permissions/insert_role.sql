-- A role of a scope.
INSERT INTO groundwork.roles (role__scope_id, role__name, role__owner)
VALUES (:role/scope-id, :role/name, :role/owner)
RETURNING role__id
