-- The bells: after a statement changes a table, announce which table, the
-- scopes and people of the changed rows, and their ids, on the channel
-- "groundwork". Postgres sends it when the transaction commits, once per
-- distinct message, and never when it rolls back. Groundwork installs one
-- trigger per table and kind of change at start (see groundwork.parquery.websocket).
--
-- Arguments: the id column ('' for none), the scope columns and the person
-- columns (each comma-separated, '' for none). Too many scopes, people or
-- ids for one message send null instead: "any".

CREATE FUNCTION groundwork.ring() RETURNS trigger LANGUAGE plpgsql AS $$
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
  PERFORM pg_notify('groundwork', json_build_object(
    't', TG_TABLE_NAME,
    's', CASE WHEN cardinality(scopes) > 100 THEN NULL ELSE scopes END,
    'p', CASE WHEN cardinality(people) > 100 THEN NULL ELSE people END,
    'i', CASE WHEN id_col = '' OR cardinality(coalesce(ids, '{}')) > 50 THEN NULL ELSE coalesce(ids, '{}') END
  )::text);
  RETURN NULL;
END $$;

-- Which SQL files a read endpoint ran, so every server knows which tables
-- its watchers wait for.
CREATE TABLE groundwork.endpoint_files (
  endpoint_file__endpoint text NOT NULL,
  endpoint_file__file     text NOT NULL,
  PRIMARY KEY (endpoint_file__endpoint, endpoint_file__file)
);
