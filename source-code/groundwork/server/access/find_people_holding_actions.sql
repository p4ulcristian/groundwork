-- Of these people, those who hold one of these actions in one of these
-- scopes of a kind: Owners hold every action.
SELECT DISTINCT m.membership__person_id AS membership__person_id
FROM groundwork.memberships m
WHERE m.membership__person_id = ANY (string_to_array(:person-ids, ',')::uuid[])
  AND m.membership__scope_kind = :scope-kind
  AND m.membership__scope_id = ANY (string_to_array(:scope-ids, ',')::uuid[])
  AND (m.membership__role = 'Owner'
       OR EXISTS (SELECT 1 FROM groundwork.role_actions ra
                  WHERE ra.role_action__scope_kind = m.membership__scope_kind
                    AND ra.role_action__scope_id   = m.membership__scope_id
                    AND ra.role_action__role       = m.membership__role
                    AND ra.role_action__action     = ANY (string_to_array(:actions, ','))))
