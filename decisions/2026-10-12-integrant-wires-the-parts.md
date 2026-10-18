# Integrant wires the parts

> **Changed in part** by [Groundwork is an opinionated framework](2026-10-12-groundwork-is-an-opinionated-framework.md) (2026-10-12): Integrant is Groundwork's own wiring and an app's settings don't list or replace parts; the test helper that swaps parts for fakes stays.

**Decision:** an app's config is an Integrant config: pure EDN, one key per part, `#ig/ref` between them. Integrant starts the parts in order, passes them to each other, stops them cleanly, and restarts them in the REPL. Groundwork checks each part's settings at start (`assert-key`) and adds a helper that starts an app with chosen parts replaced by fakes, for tests.

**Why:** the setup is pure settings data, so Groundwork can check it, print it, and compare environments directly. Integrant is mature and heavily used (millions of downloads), so its odd failures have been hit and fixed already.

**The price:** replacing a part in a test and running two of the same part (two dbs) are roundabout in Integrant; the test helper covers the first, composite keys the second.

**Instead of:**
- donut.system: direct test swaps, easy multiples, custom lifecycle steps, but far less used, and the setup holds instructions, not only settings
- Component: proven, but the wiring is code, not data
- our own: we'd own the lifecycle failures (start order, half-started systems) the others have solved
