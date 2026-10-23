# A library's keys and metrics carry its full name

> Follows [A library's namespaces start with its repository's name](2026-10-23-a-librarys-namespaces-start-with-its-repository-name.md).

**Decision:** every keyword a library owns (log events, errors, settings it gives, data it adds) is qualified by its namespace's first part, the repository's name: `:postbox/bad-email` becomes `:groundwork-email/bad-email`; every metric starts with that name in underscores: `postbox_webhook_events_total` becomes `groundwork_email_webhook_events_total`. One name for repository, namespaces, keys and metrics alike.

**Why:** a plain short qualifier (`:email/…`, `:settings/…`) is one an app may use for its own data, and the two would collide in the same map or log. Under `groundwork_` the framework's metrics sit together in Prometheus, apart from the app's.

**The price:** keys are longer in logs, in settings and in code that matches an event; dashboards and alerts are renamed once with each library.

**Instead of:** short names (`:email/bad-email`, `email_webhook_events_total`), the same shape as today (`:postbox/…`, `postbox_…`): shorter to read, but open to collisions with an app's keys and mixed in with its metrics.
