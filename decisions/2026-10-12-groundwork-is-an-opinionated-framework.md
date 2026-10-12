# Groundwork is an opinionated framework

> Replaces [Groundwork is a flexible framework](2026-10-12-groundwork-is-a-flexible-framework.md).

**Decision:** Groundwork makes the decisions and solves the hard parts. An app only adds what is its own: settings, tables and migrations, queries and endpoints, permissions (actions, scope, roles), its login, and its pages. When an app needs something new, Groundwork changes, behind a setting, and every app gets it. Inside, Groundwork is still built from parts wired by Integrant, but that is its own business: an app's settings don't list or replace parts.

**Why:** Groundwork is for my own apps. A new app should start with almost nothing left to decide. Every app runs the same tested parts, so a fix reaches all of them, and there are no per-app combinations to keep correct.

**The price:** a need only one app has still goes into Groundwork, behind a setting.

**Instead of:**
- a flexible framework where any part can be replaced: an escape hatch, but every swapped part is an app running something Groundwork never tested, and apps drift apart
- a library plus a starter template: the wiring is copied into each app and fixes never reach it
