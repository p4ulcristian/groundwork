-- A session that ends rings too, on the bells' channel: {"e": [the ended
-- sessions' key hashes, in hex]}, or null for too many ("check them all"),
-- so every server closes the websocket lines opened with them.
CREATE FUNCTION groundwork.session_ended() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  ended text[];
BEGIN
  SELECT array_agg(encode(session__key_hash, 'hex')) INTO ended FROM old_rows;
  IF ended IS NOT NULL THEN
    PERFORM pg_notify('groundwork', json_build_object(
      'e', CASE WHEN cardinality(ended) > 100 THEN NULL ELSE ended END)::text);
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER groundwork_session_ended AFTER DELETE ON groundwork.sessions
  REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION groundwork.session_ended();
