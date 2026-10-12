-- A write's one-time key: the browser sends one with every write, and a
-- repeat with the same key gets the first answer back instead of running
-- again. The answer is kept as the JSON that was sent.

CREATE TABLE groundwork.run_once (
  run_once__key        text        PRIMARY KEY,
  run_once__person_id  uuid,
  run_once__endpoint   text        NOT NULL,
  run_once__answer     text,
  run_once__created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX run_once_by_age ON groundwork.run_once (run_once__created_at);
