# A library's namespaces start with its repository's name

> Follows [A library is named by what it does](2026-10-23-a-library-is-named-by-what-it-does.md) and [A library's repository is groundwork- and its name](2026-10-23-a-librarys-repository-is-groundwork-and-its-name.md).

**Decision:** a library's namespaces start with its repository's name: the email library (github.com/p4ulcristian/groundwork-email) has its door at `groundwork-email.api`, required as `[groundwork-email.api :as email]`, its folder `source-code/groundwork_email/`. Its keys, log events and metrics follow in [their own decision](2026-10-23-a-librarys-keys-and-metrics-carry-its-full-name.md).

**Why:** one spelling of a library's name everywhere: repository, deps coordinate, folder, namespace and keys. Plain words are the words apps use for their own code (`email`, `settings`); with `groundwork-` a library's namespace never clashes with an app's. A dotted `groundwork.email` would spell the name differently from the repository and read as part of the composer's own `groundwork.*` code.

**The price:** each require's namespace is longer by `groundwork-`.

**Instead of:**
- `groundwork.email.api`: one family under `groundwork`, but the repository and the code spell the name two ways, and the composer's `groundwork.server.*` looks like a sibling
- bare plain namespaces (`email.api`): shortest, but an app with its own `email.*` or `settings.*` namespace clashes, and `logs.api` reads like the app's own code
