-- A scope's roles and their actions; the owner role holds everything and lists none.
SELECT r.role__name  AS role__name,
       r.role__owner AS role__owner,
       COALESCE((SELECT array_agg(a.role_action__action ORDER BY a.role_action__action)
                 FROM groundwork.role_actions a
                 WHERE a.role_action__role_id = r.role__id), '{}') AS role__actions
FROM groundwork.roles r
WHERE r.role__scope_id = CAST(:team/scope-id AS uuid)
ORDER BY r.role__owner DESC, r.role__name
