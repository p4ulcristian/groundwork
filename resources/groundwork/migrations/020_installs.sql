-- What migrate installed in the database from an app's settings, by part
-- (the wall with its functions, guards and fill rules; the bells'
-- triggers): the fingerprint of the statements that made it. Production's
-- start makes the same statements from its own settings and compares, so it
-- never serves on a wall its settings don't describe
-- (decisions/2026-10-18-production-migrates-before-it-starts.md).
CREATE TABLE groundwork.installs (
  install__part         text        PRIMARY KEY,
  install__fingerprint  text        NOT NULL,
  install__installed_at timestamptz NOT NULL DEFAULT now()
);
