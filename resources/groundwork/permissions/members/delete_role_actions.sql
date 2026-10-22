-- Clears a role's ticks before they're set again.
DELETE FROM groundwork.role_actions WHERE role_action__role_id = :role/id
