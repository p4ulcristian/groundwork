# Groundwork is a library

> Replaced by [Groundwork is a flexible framework](2026-10-12-groundwork-is-a-flexible-framework.md).

**Decision:** Groundwork is a library that apps depend on, not a template that copies files.

**Why:** a fix to a library reaches every app with a version bump. A template's files belong to the app once copied, and fixes never reach them.

**Instead of:** a deps-new template. A small template that starts an app on top of the library may come later.
