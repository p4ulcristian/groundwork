# REPLs by environment

> Builds on [We try queries in the REPL](2026-10-12-we-try-queries-in-the-repl.md).

**Decision:** where a REPL into a running app may exist:

- **Development:** always. One start runs the server, the browser build's watcher and the REPLs for both sides, and writes everything it prints to a log file.
- **Previews:** through a generic eval bridge: a server-to-server route, off unless a setting turns it on, never in production, behind a secret.
- **Stage:** only over SSH, never open to the network.
- **Production:** off. An emergency switch can turn one on, reachable only over SSH, and every evaluation is logged.

The files stay the truth: what is tried in a REPL and not saved to a file is gone on the next start. Tests keep the behaviour.

**Why:** a REPL into the running app is how a question gets an exact answer (try a query on real data, run an endpoint as a person, ask why a person was refused) and how code gets checked before it's committed. In production it is also a way around every permission and the database wall, so it stays off unless someone deliberately reaches it over SSH, and leaves a trace.

**Instead of:** a REPL open in every environment (anyone who reaches it reads and changes every scope's data), or none outside development (previews and stage questions answered by guessing from the code).
