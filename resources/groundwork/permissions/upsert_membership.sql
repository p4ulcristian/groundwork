-- Puts a person in a scope with a role, or changes their role there.
INSERT INTO groundwork.memberships (membership__person_id, membership__scope_id, membership__role_id)
VALUES (:membership/person-id, :membership/scope-id, :membership/role-id)
ON CONFLICT (membership__person_id, membership__scope_id)
DO UPDATE SET membership__role_id = excluded.membership__role_id
