# The ground-* libraries live in one repo

> **Changed in part** by [Libraries are folders in one tree, their edges declared](2026-10-22-libraries-are-folders-in-one-tree.md) (2026-10-22): one source tree, libraries as folders in source-code/groundwork/, edges in libraries.edn.

> **Changed in part** by [A library is named by its languages](2026-10-22-a-library-is-named-by-its-languages.md) (2026-10-22): the libraries sit in libraries/ground/<name>/.

**Decision:** Groundwork's parts become libraries named `ground-*` (`ground-schema`, `ground-access`, `ground-bells`, `ground-parquery`, `ground-styles`, and the plumbing: `ground-sql`, `ground-sessions`, `ground-jobs`, …). They live in this one repo, under `libs/ground-*`, each with its own `deps.edn` and its own tests, and are released together under one version. `groundwork` is the framework that wires them. Which parts become which library is still being planned.

**Why:** a change that crosses two libraries (a table map read by `ground-schema` and `ground-access`) is one commit and one release, never two releases that must match. Each library is still tested alone, so a change in one shows which others it breaks.

**The price:** one library can't be released on its own; every release carries all of them.

**Instead of:**
- one repo per library: separate lives, but a change touching two libraries is two PRs and two releases that must agree
- one repo for Groundwork's own ideas and one for the plumbing: the core ideas move together, but two version lines to keep in step
