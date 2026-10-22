-- Which change files have run, unless the table exists.
CREATE SCHEMA IF NOT EXISTS groundwork;
CREATE TABLE IF NOT EXISTS groundwork.changes_done (
  change_done__id text        PRIMARY KEY,
  change_done__at timestamptz NOT NULL DEFAULT now()
)
