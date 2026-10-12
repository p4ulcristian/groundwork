-- A job may carry a key: a second job with the same key, while the first is
-- still waiting or running, isn't queued (a webhook delivered twice sends one
-- email). Once done or dead, the key is free again.
ALTER TABLE groundwork.jobs ADD COLUMN job__key text;
CREATE UNIQUE INDEX jobs_one_per_key ON groundwork.jobs (job__key)
  WHERE job__key IS NOT NULL AND job__state IN ('queued', 'running');
