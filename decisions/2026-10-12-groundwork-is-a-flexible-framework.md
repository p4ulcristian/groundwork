# Groundwork is a flexible framework

> **Replaced** by [Groundwork is an opinionated framework](2026-10-12-groundwork-is-an-opinionated-framework.md) (2026-10-12): an app's settings no longer list or replace parts.

> Replaces [Groundwork is a library](2026-10-12-groundwork-is-a-library.md).

**Decision:** an app is a config map of parts. Groundwork ships the parts (the database, parquery, the server…); the app's config lists them with their settings, adds its own, and can replace any of Groundwork's with its own. Each part is also usable alone, like `pg/start`.

**Why:** Groundwork is for my own apps, and improvements should reach all of them. With parts in a config, a fix to the server, the router or the sessions arrives with a version bump; a template's copied files would freeze in each app. The config is data, like the required settings, so an app says everything it uses in one place.

**The price:** every part needs clear inputs and outputs so it can be replaced. Reading an app means following its config into Groundwork's parts.

**Instead of:**
- a library plus a starter template: the wiring is copied once into each app, and fixes never reach it
- a closed framework: nothing to wire up, but its server, router and sign-in can't be swapped
