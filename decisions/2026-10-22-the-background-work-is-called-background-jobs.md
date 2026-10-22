# The background work is called background jobs

> **Changed in part** by [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md) (2026-10-22): the library is backroom (`backroom.api`), its tables `backroom.background_jobs` and `backroom.background_job_schedules`; a unit of work is still a background job.

> Replaces [The background work is called chores](2026-10-22-the-background-work-is-called-chores.md).

**Decision:** a piece of work saved to run outside a request is a **background job**, never "job" alone. The library is `groundwork.server.background-jobs` in `source-code/groundwork/server/background_jobs/`; its tables are `groundwork.background_jobs` and `groundwork.background_job_schedules`, its columns `background_job__…`, its keys `:background-job/…`, and its functions say background job (`add-background-job-to-queue!`, `list-dead-background-jobs`, `run-background-job-again-now!`). A background job kind is a map, as before.

**Why:** "background jobs" is the name people know (Sidekiq, Oban, Rails); "chores" had to be learned. Iron Rainbow's job is a coating job (`:job/…`, `job__…`), so the word is always the whole "background job": `:background-job/id` and `:job/id` can't be mixed up.

**The price:** longer names than `:job/…`.

**Instead of:**
- chores: no clash, but a word people have to learn
- job alone: clashes with Iron Rainbow's coating job
