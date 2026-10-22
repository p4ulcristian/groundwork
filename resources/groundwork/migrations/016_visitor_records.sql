-- A record can belong to a visitor: the transaction carries the id of the
-- visitor's session (Groundwork's own, never a value from the request), and
-- the wall shows a row with that id in its visitor column to that visitor.
-- It ends with the transaction.
CREATE FUNCTION groundwork.wall_visitor()
RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('groundwork.visitor', true), '')::uuid
$$;
