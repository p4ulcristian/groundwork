-- A scope's role by its name, with its actions.
SELECT r.role__id, r.role__owner,
       coalesce(array_agg(a.role_action__action) FILTER (WHERE a.role_action__action IS NOT NULL), '{}') AS role__actions
FROM groundwork.roles r
LEFT JOIN groundwork.role_actions a ON a.role_action__role_id = r.role__id
WHERE r.role__scope_id = :role/scope-id AND r.role__name = :role/name
GROUP BY r.role__id, r.role__owner
