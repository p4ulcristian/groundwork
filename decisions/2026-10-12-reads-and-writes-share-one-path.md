# Reads and writes share one path

**Decision:** reads and writes are endpoints in one list, through one door, under one permission rule. Their difference comes from the "read or write" declaration: a read runs in a read-only transaction, a write commits once, runs once per key, and names what it touches. In the browser, reads are kept by question; writes are sent once and never kept or repeated by themselves, and their effect reaches screens through live updates.

**Why:** one way to define an endpoint and one place to look. A read can't change anything, because its transaction is read-only, so a separate door would only add a second set of rules to keep in step.

**Instead of:** a separate path and list for writes.
