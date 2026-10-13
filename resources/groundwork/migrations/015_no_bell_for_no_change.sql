-- A statement that changed no row rings nothing. The triggers run once per
-- statement, also for an UPDATE or DELETE that matched nothing and an
-- INSERT … ON CONFLICT DO NOTHING that added nothing; a bell then named no
-- scope and no person, which for a global table means everyone watching it:
-- a read that made sure of a row (changing nothing) rang its own watchers,
-- who asked again, and so on.

CREATE OR REPLACE FUNCTION groundwork.ring() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  id_col      text   := TG_ARGV[0];
  scope_cols  text[] := array_remove(string_to_array(TG_ARGV[1], ','), '');
  person_cols text[] := array_remove(string_to_array(TG_ARGV[2], ','), '');
  source      text;
  col         text;
  found       text[];
  changed     boolean;
  scopes      text[] := '{}';
  people      text[] := '{}';
  ids         text[] := '{}';
BEGIN
  source := CASE TG_OP
              WHEN 'INSERT' THEN 'new_rows'
              WHEN 'DELETE' THEN 'old_rows'
              ELSE '(SELECT * FROM new_rows UNION ALL SELECT * FROM old_rows)'
            END;
  EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s x)', source) INTO changed;
  IF NOT changed THEN
    RETURN NULL;
  END IF;
  FOREACH col IN ARRAY scope_cols LOOP
    EXECUTE format('SELECT array_agg(DISTINCT %I::text) FROM %s x WHERE %I IS NOT NULL', col, source, col) INTO found;
    scopes := scopes || coalesce(found, '{}');
  END LOOP;
  FOREACH col IN ARRAY person_cols LOOP
    EXECUTE format('SELECT array_agg(DISTINCT %I::text) FROM %s x WHERE %I IS NOT NULL', col, source, col) INTO found;
    people := people || coalesce(found, '{}');
  END LOOP;
  IF id_col <> '' THEN
    EXECUTE format('SELECT array_agg(DISTINCT %I::text) FROM %s x', id_col, source) INTO ids;
  END IF;
  PERFORM groundwork.announce(json_build_object(
    't', CASE WHEN TG_TABLE_SCHEMA = 'public' THEN TG_TABLE_NAME ELSE TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME END,
    's', CASE WHEN cardinality(scopes) > 100 THEN NULL ELSE scopes END,
    'p', CASE WHEN cardinality(people) > 100 THEN NULL ELSE people END,
    'i', CASE WHEN id_col = '' OR cardinality(coalesce(ids, '{}')) > 50 THEN NULL ELSE coalesce(ids, '{}') END
  ));
  RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION groundwork.ring_role_actions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  source  text := CASE TG_OP WHEN 'INSERT' THEN 'new_rows' ELSE 'old_rows' END;
  changed boolean;
  scopes  text[];
  people  text[];
BEGIN
  EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s x)', source) INTO changed;
  IF NOT changed THEN
    RETURN NULL;
  END IF;
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
