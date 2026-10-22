# Groundwork's libraries share the GitHub topic groundwork

> **Changed in part** by [Groundwork is renamed house, and the group of libraries is manor](2026-10-22-groundwork-is-renamed-house-and-the-group-is-manor.md) (2026-10-22): the topic is `manor` (`user:p4ulcristian topic:manor`), and the repository groundwork is house.

**Decision:** every repository Groundwork is made of (groundwork, wire, cellar, backroom, showroom, mailroom, doorman, doorbell, and each library cut out after them) carries the GitHub topic `groundwork`. One search lists them all: `user:p4ulcristian topic:groundwork`. A new library gets the topic when its repository is made. Each library stays a plain repository of its own; Groundwork takes it from git by tag in `deps.edn`.

**Why:** the libraries are cut out one by one, several a day; the topic keeps them findable together without changing how they are taken or where they live.

**Instead of:**
- the libraries as git submodules in `libraries/` (in for an afternoon, 2026-10-22): a second pin beside the tag in `deps.edn`, and the wish was only to keep them together
- one local folder for them all: their places on disk change, and it groups only this machine
- a GitHub organization: every `deps.edn` coordinate renames; Groundwork is private, and a free organization has no protected main for private repositories
