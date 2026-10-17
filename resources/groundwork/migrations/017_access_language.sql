-- The access language: who may do what is one list of sentences, and the
-- wall is made from them per table and command (read, insert, update,
-- delete), no longer per action. A transaction carries:
--
--   groundwork.scopes        the scopes verified for its reads, or '*' for every scope
--   groundwork.write_scopes  the scopes it may write in: the verified ones but those a
--                            scope's state denies writing in, or '*'
--   groundwork.whole_scopes  work done for scopes rather than a person (a job): every
--                            row of them, to read and to write
--   groundwork.flags         the session's flags, for the denies that name one
--
-- with the person and the visitor as before. All of it ends with the
-- transaction. The functions the wall of 002 and 007 used stay for the
-- policies an earlier start installed; the start replaces those.

-- A row's scope is verified for reading.
CREATE OR REPLACE FUNCTION groundwork.wall_allows(scope uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('groundwork.bypass', true), '') = 'on'
      OR (scope IS NOT NULL
          AND (coalesce(current_setting('groundwork.scopes', true), '') = '*'
               OR scope = ANY (coalesce(nullif(nullif(current_setting('groundwork.scopes', true), ''), '*'), '{}')::uuid[])))
$$;

-- A row's scope may be written in.
CREATE FUNCTION groundwork.wall_writes(scope uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('groundwork.bypass', true), '') = 'on'
      OR (scope IS NOT NULL
          AND (coalesce(current_setting('groundwork.write_scopes', true), '') = '*'
               OR scope = ANY (coalesce(nullif(nullif(current_setting('groundwork.write_scopes', true), ''), '*'), '{}')::uuid[])))
$$;

-- Work done for whole scopes (a job that names its scope): every row of them.
CREATE FUNCTION groundwork.wall_whole(scope uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT scope IS NOT NULL
     AND scope = ANY (coalesce(nullif(current_setting('groundwork.whole_scopes', true), ''), '{}')::uuid[])
$$;

-- Whether the session carries a flag, for a deny that names it.
CREATE FUNCTION groundwork.wall_flag(flag text)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT flag = ANY (coalesce(nullif(current_setting('groundwork.flags', true), ''), '{}')::text[])
$$;

-- Whether the transaction runs as the system.
CREATE FUNCTION groundwork.wall_bypass()
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('groundwork.bypass', true), '') = 'on'
$$;

-- Every scope where a person holds any of these actions: one lookup for
-- the sentences that differ only in the action they need.
CREATE FUNCTION groundwork.scopes_holding_any(person uuid, actions text[])
RETURNS SETOF uuid LANGUAGE sql STABLE AS $$
  WITH RECURSIVE held(scope) AS (
    SELECT m.membership__scope_id
    FROM groundwork.memberships m
    JOIN groundwork.roles r ON r.role__id = m.membership__role_id
    WHERE m.membership__person_id = person
      AND (r.role__owner OR EXISTS (SELECT 1 FROM groundwork.role_actions a
                                    WHERE a.role_action__role_id = r.role__id
                                      AND a.role_action__action = ANY (actions)))
    UNION
    SELECT e.extra_action__scope_id
    FROM groundwork.extra_actions e
    WHERE e.extra_action__person_id = person AND e.extra_action__action = ANY (actions)
    UNION
    SELECT s.scope__id
    FROM groundwork.scopes s
    JOIN held h ON s.scope__parent_id = h.scope
  )
  SELECT scope FROM held
$$;

-- A scope kind may have exactly one scope (a platform's back office).
-- Groundwork refuses a second one in create-scope!; finding it is one lookup.
CREATE INDEX scopes_by_kind ON groundwork.scopes (scope__kind);

-- The bells learn of universal rows: a fourth argument, 'u', for a table
-- whose rows may belong to nobody. A changed row with no scope at all then
-- rings everyone watching the table ("u": true), like a global table.
CREATE OR REPLACE FUNCTION groundwork.ring() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  id_col      text   := TG_ARGV[0];
  scope_cols  text[] := array_remove(string_to_array(TG_ARGV[1], ','), '');
  person_cols text[] := array_remove(string_to_array(TG_ARGV[2], ','), '');
  universal   boolean := TG_NARGS > 3 AND TG_ARGV[3] = 'u';
  source      text;
  col         text;
  found       text[];
  changed     boolean;
  nobodys     boolean := false;
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
  IF universal THEN
    IF cardinality(scope_cols) = 0 THEN
      nobodys := true;
    ELSE
      EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s x WHERE %s)', source,
                     (SELECT string_agg(format('%I IS NULL', c), ' AND ') FROM unnest(scope_cols) c))
        INTO nobodys;
    END IF;
  END IF;
  IF id_col <> '' THEN
    EXECUTE format('SELECT array_agg(DISTINCT %I::text) FROM %s x', id_col, source) INTO ids;
  END IF;
  PERFORM groundwork.announce(json_build_object(
    't', CASE WHEN TG_TABLE_SCHEMA = 'public' THEN TG_TABLE_NAME ELSE TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME END,
    's', CASE WHEN cardinality(scopes) > 100 THEN NULL ELSE scopes END,
    'p', CASE WHEN cardinality(people) > 100 THEN NULL ELSE people END,
    'i', CASE WHEN id_col = '' OR cardinality(coalesce(ids, '{}')) > 50 THEN NULL ELSE coalesce(ids, '{}') END,
    'u', nobodys
  ));
  RETURN NULL;
END $$;
