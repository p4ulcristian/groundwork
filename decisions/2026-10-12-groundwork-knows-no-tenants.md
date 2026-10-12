# Groundwork knows nothing about tenants

> Replaced by [Groundwork ships permissions](2026-10-12-groundwork-ships-permissions.md).

**Decision:** multi-tenancy, shops, workspaces and roles belong to the app. Groundwork gives hooks (like `:on-transaction`) where the app plugs them in.

**Why:** Groundwork is the part every app shares. Who belongs where is different in every app.
