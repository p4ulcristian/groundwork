-- A scope and every scope above it, nearest first, with the person's role in
-- each (if any), whether the role holds the action, and whether the person
-- has it as an extra action there.
WITH RECURSIVE up(scope, depth) AS (
  SELECT CAST(:scope/id AS uuid), 0
  UNION
  SELECT s.scope__parent_id, u.depth + 1
  FROM groundwork.scopes s JOIN up u ON s.scope__id = u.scope
  WHERE s.scope__parent_id IS NOT NULL
)
SELECT u.scope       AS why__scope_id,
       s.scope__kind AS why__kind,
       u.depth       AS why__depth,
       r.role__name  AS why__role,
       r.role__owner AS why__owner,
       EXISTS (SELECT 1 FROM groundwork.role_actions a
               WHERE a.role_action__role_id = r.role__id AND a.role_action__action = :action) AS why__role_holds,
       EXISTS (SELECT 1 FROM groundwork.extra_actions e
               WHERE e.extra_action__person_id = CAST(:person AS uuid)
                 AND e.extra_action__scope_id = u.scope
                 AND e.extra_action__action = :action) AS why__extra
FROM up u
JOIN groundwork.scopes s ON s.scope__id = u.scope
LEFT JOIN groundwork.memberships m ON m.membership__scope_id = u.scope
                                  AND m.membership__person_id = CAST(:person AS uuid)
LEFT JOIN groundwork.roles r ON r.role__id = m.membership__role_id
ORDER BY u.depth
