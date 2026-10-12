# Groundwork knows nothing about tenants

**Decision:** multi-tenancy, shops, workspaces and roles belong to the app. Groundwork gives hooks (like `:on-transaction`) where the app plugs them in.

**Why:** Groundwork is the part every app shares. Who belongs where is different in every app.
