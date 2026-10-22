-- Groundwork's schema and its record of migrations, before any migration runs.
CREATE SCHEMA IF NOT EXISTS groundwork;

CREATE TABLE IF NOT EXISTS groundwork.migrations (
  migration__source      text        NOT NULL,
  migration__file        text        NOT NULL,
  migration__fingerprint text        NOT NULL,
  migration__ran_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (migration__source, migration__file)
);
