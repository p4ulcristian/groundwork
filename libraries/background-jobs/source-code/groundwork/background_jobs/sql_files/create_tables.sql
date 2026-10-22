-- The queue and the schedules, unless they exist. Their shape is the old
-- Groundwork's, so its apps' tables are the same.
CREATE SCHEMA IF NOT EXISTS groundwork;

CREATE TABLE IF NOT EXISTS groundwork.jobs (
  job__id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  job__kind         text        NOT NULL,
  job__params       jsonb       NOT NULL,
  job__state        text        NOT NULL DEFAULT 'queued'
                    CHECK (job__state IN ('queued', 'running', 'done', 'dead')),
  job__run_at       timestamptz NOT NULL DEFAULT now(),
  job__attempts     integer     NOT NULL DEFAULT 0,
  job__max_attempts integer     NOT NULL,
  job__timeout_ms   integer     NOT NULL,
  job__last_error   text,
  job__locked_at    timestamptz,
  job__created_at   timestamptz NOT NULL DEFAULT now(),
  job__finished_at  timestamptz,
  job__key          text
);

CREATE INDEX IF NOT EXISTS jobs_due ON groundwork.jobs (job__run_at) WHERE job__state = 'queued';
CREATE UNIQUE INDEX IF NOT EXISTS jobs_one_per_key ON groundwork.jobs (job__key) WHERE job__key IS NOT NULL;

CREATE TABLE IF NOT EXISTS groundwork.job_schedules (
  job_schedule__key     text        PRIMARY KEY,
  job_schedule__next_at timestamptz NOT NULL
);
