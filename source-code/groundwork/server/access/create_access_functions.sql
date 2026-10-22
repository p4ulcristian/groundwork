-- Who is asking, as the gates and the check read it: one setting per
-- transaction, groundwork.asking, set by set_asking.sql:
--   {"person": "uuid or null", "holding": {"shop orders/see": ["scope id", …]}}
CREATE SCHEMA IF NOT EXISTS groundwork;

CREATE OR REPLACE FUNCTION groundwork.find_asking_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('groundwork.asking', true), '')::jsonb ->> 'person')::uuid
$$;

CREATE OR REPLACE FUNCTION groundwork.find_scopes_holding(kind text, action text) RETURNS uuid[]
LANGUAGE sql STABLE AS $$
  SELECT coalesce(array(SELECT jsonb_array_elements_text(
                          NULLIF(current_setting('groundwork.asking', true), '')::jsonb -> 'holding' -> (kind || ' ' || action)
                        )::uuid),
                  '{}')
$$
