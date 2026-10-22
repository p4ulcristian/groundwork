-- The queue and the schedules, unless they exist.
CREATE SCHEMA IF NOT EXISTS groundwork;

CREATE TABLE IF NOT EXISTS groundwork.chores (
  chore__id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  chore__kind         text        NOT NULL,
  chore__params       jsonb       NOT NULL,
  chore__state        text        NOT NULL DEFAULT 'queued'
                    CHECK (chore__state IN ('queued', 'running', 'done', 'dead')),
  chore__run_at       timestamptz NOT NULL DEFAULT now(),
  chore__attempts     integer     NOT NULL DEFAULT 0,
  chore__max_attempts integer     NOT NULL,
  chore__timeout_ms   integer     NOT NULL,
  chore__last_error   text,
  chore__locked_at    timestamptz,
  chore__created_at   timestamptz NOT NULL DEFAULT now(),
  chore__finished_at  timestamptz,
  chore__key          text
);

CREATE INDEX IF NOT EXISTS chores_due ON groundwork.chores (chore__run_at) WHERE chore__state = 'queued';
CREATE UNIQUE INDEX IF NOT EXISTS chores_one_per_key ON groundwork.chores (chore__key) WHERE chore__key IS NOT NULL;

CREATE TABLE IF NOT EXISTS groundwork.chore_schedules (
  chore_schedule__key     text        PRIMARY KEY,
  chore_schedule__next_at timestamptz NOT NULL
);
