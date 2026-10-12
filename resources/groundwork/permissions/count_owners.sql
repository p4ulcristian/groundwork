-- How many people own a scope.
SELECT count(*) AS owners__count
FROM groundwork.memberships m
JOIN groundwork.roles r ON r.role__id = m.membership__role_id
WHERE m.membership__scope_id = :scope/id AND r.role__owner
