-- Groundwork's own team tables ring too, so a team screen and the browser's
-- "can I?" follow a change: memberships, extra actions and invitations by
-- their scope and person, roles by their scope, and a role's actions by the
-- role's scope and the people who hold the role (their grants changed).

CREATE TRIGGER groundwork_ring_insert AFTER INSERT ON groundwork.memberships
  REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'membership__scope_id', 'membership__person_id');
CREATE TRIGGER groundwork_ring_update AFTER UPDATE ON groundwork.memberships
  REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'membership__scope_id', 'membership__person_id');
CREATE TRIGGER groundwork_ring_delete AFTER DELETE ON groundwork.memberships
  REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'membership__scope_id', 'membership__person_id');

CREATE TRIGGER groundwork_ring_insert AFTER INSERT ON groundwork.extra_actions
  REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'extra_action__scope_id', 'extra_action__person_id');
CREATE TRIGGER groundwork_ring_update AFTER UPDATE ON groundwork.extra_actions
  REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'extra_action__scope_id', 'extra_action__person_id');
CREATE TRIGGER groundwork_ring_delete AFTER DELETE ON groundwork.extra_actions
  REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'extra_action__scope_id', 'extra_action__person_id');

CREATE TRIGGER groundwork_ring_insert AFTER INSERT ON groundwork.invitations
  REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'invitation__scope_id', '');
CREATE TRIGGER groundwork_ring_update AFTER UPDATE ON groundwork.invitations
  REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'invitation__scope_id', '');
CREATE TRIGGER groundwork_ring_delete AFTER DELETE ON groundwork.invitations
  REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'invitation__scope_id', '');

CREATE TRIGGER groundwork_ring_insert AFTER INSERT ON groundwork.roles
  REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'role__scope_id', '');
CREATE TRIGGER groundwork_ring_update AFTER UPDATE ON groundwork.roles
  REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'role__scope_id', '');
CREATE TRIGGER groundwork_ring_delete AFTER DELETE ON groundwork.roles
  REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT
  EXECUTE FUNCTION groundwork.ring('', 'role__scope_id', '');

-- A role's actions have no scope or person of their own: both come from the role.
CREATE FUNCTION groundwork.ring_role_actions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  source text := CASE TG_OP WHEN 'INSERT' THEN 'new_rows' ELSE 'old_rows' END;
  scopes text[];
  people text[];
BEGIN
  EXECUTE format('SELECT array_agg(DISTINCT role__scope_id::text) FROM groundwork.roles
                  WHERE role__id IN (SELECT role_action__role_id FROM %s)', source) INTO scopes;
  EXECUTE format('SELECT array_agg(DISTINCT membership__person_id::text) FROM groundwork.memberships
                  WHERE membership__role_id IN (SELECT role_action__role_id FROM %s)', source) INTO people;
  scopes := coalesce(scopes, '{}');
  people := coalesce(people, '{}');
  PERFORM groundwork.announce(json_build_object(
    't', 'groundwork.role_actions',
    's', CASE WHEN cardinality(scopes) > 100 THEN NULL ELSE scopes END,
    'p', CASE WHEN cardinality(people) > 100 THEN NULL ELSE people END,
    'i', NULL));
  RETURN NULL;
END $$;

CREATE TRIGGER groundwork_ring_insert AFTER INSERT ON groundwork.role_actions
  REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION groundwork.ring_role_actions();
CREATE TRIGGER groundwork_ring_delete AFTER DELETE ON groundwork.role_actions
  REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION groundwork.ring_role_actions();
