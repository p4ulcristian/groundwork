-- groundwork.tell_sync(id column, column, …): called by a table's change
-- triggers after each statement; tells every listening server which rows
-- changed, on the channel groundwork_sync, when the transaction commits:
--   {"folder": "public", "table": "orders", "ids": ["…"], "columns": {"order__shop_id": ["…"]}}
-- ids: the changed rows' id column (left out when the table has none);
-- columns: the distinct values of each column named, before and after.
-- A statement that changed no row tells nothing. Past 7500 characters
-- (Postgres takes 8000) only the table is told, with "all": true.
CREATE SCHEMA IF NOT EXISTS groundwork;

CREATE OR REPLACE FUNCTION groundwork.tell_sync() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  rows_sql text;
  changed  bigint;
  told     jsonb;
  columns  jsonb := '{}';
  i        int;
BEGIN
  rows_sql := CASE TG_OP
                WHEN 'INSERT' THEN 'SELECT * FROM cellar_new_rows'
                WHEN 'DELETE' THEN 'SELECT * FROM cellar_old_rows'
                ELSE 'SELECT * FROM cellar_new_rows UNION ALL SELECT * FROM cellar_old_rows'
              END;
  EXECUTE format('SELECT count(*) FROM (%s) changed', rows_sql) INTO changed;
  IF changed = 0 THEN
    RETURN NULL;
  END IF;
  told := jsonb_build_object('folder', TG_TABLE_SCHEMA, 'table', TG_TABLE_NAME);
  IF TG_ARGV[0] <> '' THEN
    EXECUTE format('SELECT $1 || jsonb_build_object(''ids'', jsonb_agg(DISTINCT %I::text)) FROM (%s) changed',
                   TG_ARGV[0], rows_sql)
      INTO told USING told;
  END IF;
  FOR i IN 1 .. TG_NARGS - 1 LOOP
    EXECUTE format('SELECT $1 || jsonb_build_object(%L, coalesce(jsonb_agg(DISTINCT %I::text) FILTER (WHERE %I IS NOT NULL), ''[]''))
                    FROM (%s) changed', TG_ARGV[i], TG_ARGV[i], TG_ARGV[i], rows_sql)
      INTO columns USING columns;
  END LOOP;
  told := told || jsonb_build_object('columns', columns);
  IF length(told::text) > 7500 THEN
    told := jsonb_build_object('folder', TG_TABLE_SCHEMA, 'table', TG_TABLE_NAME, 'all', true);
  END IF;
  PERFORM pg_notify('groundwork_sync', told::text);
  RETURN NULL;
END
$$
