-- A write's answer carries its own bells. An endpoint's write names its
-- transaction with a tag (the setting groundwork.tag, for the transaction
-- only); every bell rung in it is then also kept in groundwork.rang, one
-- JSON line each, for the endpoint to read before it commits, and carries
-- the tag ("g") on the channel, so the browser that wrote can tell the
-- websocket's bell for the same commit from news. Kept bells past about
-- 60 000 characters become 'any': everything may have changed.
--
-- A table outside the app's own schema rings under its schema's name,
-- "groundwork.memberships", so it can't be taken for an app's table.

CREATE FUNCTION groundwork.announce(bell json) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  tag  text := nullif(current_setting('groundwork.tag', true), '');
  rang text;
BEGIN
  IF tag IS NULL THEN
    PERFORM pg_notify('groundwork', bell::text);
  ELSE
    PERFORM pg_notify('groundwork', (bell::jsonb || jsonb_build_object('g', tag))::text);
    rang := coalesce(current_setting('groundwork.rang', true), '');
    IF rang = 'any' OR length(rang) > 60000 THEN
      PERFORM set_config('groundwork.rang', 'any', true);
    ELSE
      PERFORM set_config('groundwork.rang',
                         rang || CASE WHEN rang = '' THEN '' ELSE E'\n' END || (bell::jsonb)::text,
                         true);
    END IF;
  END IF;
END $$;

CREATE OR REPLACE FUNCTION groundwork.ring() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  id_col      text   := TG_ARGV[0];
  scope_cols  text[] := array_remove(string_to_array(TG_ARGV[1], ','), '');
  person_cols text[] := array_remove(string_to_array(TG_ARGV[2], ','), '');
  source      text;
  col         text;
  found       text[];
  scopes      text[] := '{}';
  people      text[] := '{}';
  ids         text[] := '{}';
BEGIN
  source := CASE TG_OP
              WHEN 'INSERT' THEN 'new_rows'
              WHEN 'DELETE' THEN 'old_rows'
              ELSE '(SELECT * FROM new_rows UNION ALL SELECT * FROM old_rows)'
            END;
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
