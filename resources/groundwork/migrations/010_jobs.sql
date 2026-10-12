-- Background jobs: created in the app's own transaction, claimed by one
-- worker at a time (FOR UPDATE SKIP LOCKED), retried, and kept when dead.
CREATE TABLE groundwork.jobs (
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
  job__finished_at  timestamptz
);

CREATE INDEX jobs_due ON groundwork.jobs (job__run_at) WHERE job__state = 'queued';

-- Schedules: the next time each runs. A server claims a due one with
-- FOR UPDATE SKIP LOCKED and moves it on, so it runs once across servers.
CREATE TABLE groundwork.job_schedules (
  schedule__key     text        PRIMARY KEY,
  schedule__next_at timestamptz NOT NULL
);
