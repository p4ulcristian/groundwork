# The background work is called chores: groundwork.background-chores

> Replaces [The jobs library is named groundwork.background-jobs](2026-10-22-the-jobs-library-is-named-background-jobs.md): a job is a chore, the library is `groundwork.background-chores`.

**Decision:** a piece of work saved to run outside a request is a **chore**. The library is `groundwork.background-chores` in `libraries/background-chores/`; its tables are `groundwork.chores` and `groundwork.chore_schedules`, its columns `chore__…`, its keys `:chore/…`, and its functions say chore (`add-chore-to-queue!`, `list-dead-chores`, `run-chore-again-now!`). A chore kind is a map, as before.

**Why:** in Iron Rainbow a job is a coating job (`:job/…`, `job__…` everywhere), and a task is what its task board shows. A word that means one thing everywhere (see [GLOSSARY.md](../GLOSSARY.md)) can't be either. "Chore" is plain English for a piece of routine work, and Iron Rainbow doesn't use it.

**The price:** "background jobs" is the name people know; "chores" has to be read once.

**Instead of:**
- job: Iron Rainbow's coating job
- task: Iron Rainbow's task board
- errand: free too, but reads as going somewhere
- work item: free too, but reads as a ticket
