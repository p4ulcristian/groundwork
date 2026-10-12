# Groundwork is a library

**Decision:** Groundwork is a library that apps depend on, not a template that copies files.

**Why:** a fix to a library reaches every app with a version bump. A template's files belong to the app once copied, and fixes never reach them.

**Instead of:** a deps-new template. A small template that starts an app on top of the library may come later.
