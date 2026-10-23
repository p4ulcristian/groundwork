# The jobs library is named groundwork.background-jobs

> **Replaced** by [The background work is called chores](2026-10-22-the-background-work-is-called-chores.md) (2026-10-22): a job is a chore; the library is `groundwork.background-chores`.

> Changes in part [The first cut is thirteen libraries](2026-10-22-the-first-cut-is-thirteen-libraries.md): `ground-jobs` is `groundwork.background-jobs`.

**Decision:** the library for work that runs outside a request is `groundwork.background-jobs`, in `libraries/background-jobs/`. It holds three parts, all in Postgres: the queue (a job saved as a row, so the request answers right away), the workers (they take jobs from the queue and run them, retry a failure, and let a job give up for good), and the schedules (each puts a job in the queue at its time, like every night at 01:00).

**Why:** "jobs" alone reads as a scheduler, which is one of its three parts. "Background jobs" is the common name for a queue with workers, and says the work runs apart from the request.

**Instead of:**
- jobs: short, but reads as a scheduler
- queue: names the middle part only; the workers and schedules aren't a queue
- background-work: true, but not the name people know
