# The start and the deploy's migrate refuse a view that reads past the gates

> 2026-11-01, asked for by the switch of Iron Rainbow onto composer v8 (its views, made by an owner with BYPASSRLS, handed every row to whoever read them). Since v8.0.6, with groundwork-access v0.8.6 ([A view the app's role reads runs as whoever asks](https://github.com/p4ulcristian/groundwork-access/blob/main/decisions/2026-11-01-a-view-the-apps-role-reads-runs-as-whoever-asks.md)). Adds to [One start, and a migrate for the deploy](server/main/2026-10-22-one-start-and-a-migrate-for-the-deploy.md).

**Decision:** groundwork runs groundwork-access's `check-views` on the tables it makes ready (the app's and its own), in three places:

- **the start, in production** (`:migrate-at-start? false`): after the role check and the check that the database is current, on the app's own pool;
- **the start, in development** (`:migrate-at-start? true`): after its migrate;
- **`migrate-app!`**, the deploy's: after migrate and the grants, as the owner, so a deploy stops at its migrate with the views named, not later at the start.

Each refuses `:groundwork-access/views-past-the-gates` with every view at once: a view in the tables' folders that isn't `security_invoker`, or a materialized view there.

**Why:** a view runs as its owner unless it is `security_invoker`, and the owner the deploy migrates as may pass every gate (BYPASSRLS, or a superuser in tests): the app would hand every row under such a view to whoever reads it, and only production would show it. The start already refuses a role that passes the gates; a view that does the same for whoever reads it is the same hole, so it is refused the same way, in every start, development's too, where the hole doesn't show.

**The price:** an app's view files say `WITH (security_invoker = true)`; a report another role reads past the gates goes in a folder of its own, which the app's role is never granted.

**Instead of:** checking only in production's start (development and the tests, whose view owners happen to be the app's role or a superuser, would never see it); checking only in `migrate-app!` (a database migrated by hand, or by an older deploy, would still start).
