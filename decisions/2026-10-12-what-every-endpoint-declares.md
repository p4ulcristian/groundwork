# What every endpoint declares

**Decision:** every endpoint declares four things, or the app doesn't start:

- its **permission** (see Groundwork ships permissions)
- its **input shape:** what the browser may send. Anything that doesn't fit is refused before any work runs. The shape marks which fields are ids, and of what kind, so every id in a request is checked.
- its **answer shape** (see Every endpoint declares its answer)
- **read or write:** a read runs in a read-only transaction; a write runs in one transaction that commits only when everything succeeds, its answer passing the answer shape included

**Optional:** what a read shows or a write touches, as labels. Live updates come from Postgres itself (see Postgres rings the bell); labels are only for what Postgres can't see, such as a read that calls a database function or data outside the database.

**Run-once is automatic for every write:** the browser half sends a one-time key with each write; a repeat with the same key gets the first answer back instead of running again.

**Why:** each declaration closes a door: unchecked input reaching a query, ids from another scope, a half-saved write, a double-clicked order. Required, none of them can be forgotten.

**Instead of:** optional declarations, where an endpoint without one silently does less.
