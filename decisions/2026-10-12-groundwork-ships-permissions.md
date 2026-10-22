# Groundwork ships permissions

> **Changed in part** by doorman's [doorman ships permissions](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-doorman-ships-permissions.md) (2026-10-22): the permission model, its shapes, what is stored and what is built in from the first handler moved there whole, with [Who may see what is the app's; doorman enforces it](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-15-who-may-see-what-is-the-apps.md) and [Access rules are sentences in doorman's language](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-17-access-rules-are-sentences-in-doormans-language.md), which change it. What stays here is what Groundwork ships.

> Replaces [Groundwork knows nothing about tenants](2026-10-12-groundwork-knows-no-tenants.md): Groundwork knows about scopes, not what they are.

**Decision:** permissions are a part Groundwork ships, built so they can't be skipped: doorman, put in front of every handler by groundwork.server.main, its gates on every table that names a scope. Two parts of the first design reach past doorman:

- **Open browsers are told** when what they show changes: doorbell's, which tells every open screen which table changed and leaves to the gates what each gets when it asks again.
- **The browser's "can I?"** comes from the same grants and rules; not built yet.

**Why:** every app needs this, and a mistake is a data leak; a framework that ships it required, from the first handler, leaves no app to add it later.

**Instead of:** an app choosing whether to switch permissions on.
