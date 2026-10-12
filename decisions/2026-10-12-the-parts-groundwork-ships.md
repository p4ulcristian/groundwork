# The parts Groundwork ships

> Since [Groundwork is an opinionated framework](2026-10-12-groundwork-is-an-opinionated-framework.md), apps don't replace parts. Added: [permissions](2026-10-12-groundwork-ships-permissions.md) and [the page shell](2026-10-12-groundwork-ships-the-page-shell.md).

**Decision:** Groundwork ships these parts:

- **db:** the Postgres pool, queries and transactions
- **parquery:** the server handler (registry, checks, batches), with live updates as part of it, switched on in the config
- **parquery's browser half:** asking, the query store, subscriptions (its name is still open)
- **server:** the web server, on virtual threads
- **router:** routes as data in, one handler out; mounts parquery next to the app's own routes
- **migrations:** changing the database schema in steps
- **sessions:** remembering who is signed in (the cookie and its storage)

Not Groundwork's: **login** (the app writes the user into the session and says who is asking through parquery's `:context` function), **logs and metrics** (through hooks like `:on-query`).

Later: **background jobs**, a queue in Postgres ([#1](https://github.com/p4ulcristian/groundwork/issues/1)). Still to decide: the **page shell**, and how it stays replaceable.

**Why:** these are the same in every app. What differs per app plugs in as a function or as its own part. Live updates live in parquery because the registry already says what each read shows and what each write touches, and who may hear about it.

**Instead of:** shipping login (different in every app), or keeping live updates as a separate part (topics and permissions declared in two places that must agree).
