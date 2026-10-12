ALTER TABLE widgets ADD COLUMN widget__name text;
-- a second statement in the same file
CREATE INDEX widgets_by_name ON widgets (widget__name);
