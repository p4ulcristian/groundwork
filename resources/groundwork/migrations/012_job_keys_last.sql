-- A job's key holds for as long as the job is remembered, done or dead too
-- (done jobs are kept a week): a webhook delivered again an hour later still
-- sends one email.
DROP INDEX groundwork.jobs_one_per_key;
CREATE UNIQUE INDEX jobs_one_per_key ON groundwork.jobs (job__key) WHERE job__key IS NOT NULL;
