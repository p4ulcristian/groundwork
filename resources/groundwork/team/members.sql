-- A scope's members: their role, whether it's the owner role, and their extra actions.
SELECT m.membership__person_id AS member__person_id,
       r.role__name            AS member__role,
       r.role__owner           AS member__owner,
       COALESCE((SELECT array_agg(e.extra_action__action ORDER BY e.extra_action__action)
                 FROM groundwork.extra_actions e
                 WHERE e.extra_action__person_id = m.membership__person_id
                   AND e.extra_action__scope_id = m.membership__scope_id), '{}') AS member__extra_actions
FROM groundwork.memberships m
JOIN groundwork.roles r ON r.role__id = m.membership__role_id
WHERE m.membership__scope_id = CAST(:team/scope-id AS uuid)
ORDER BY r.role__owner DESC, r.role__name, m.membership__person_id
