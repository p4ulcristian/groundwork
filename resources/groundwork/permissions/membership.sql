-- A person's role in a scope, if they belong there.
SELECT r.role__id, r.role__name, r.role__owner
FROM groundwork.memberships m
JOIN groundwork.roles r ON r.role__id = m.membership__role_id
WHERE m.membership__person_id = :membership/person-id AND m.membership__scope_id = :membership/scope-id
