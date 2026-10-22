# wire and cellar sit in libraries/ as submodules

> Adds to [wire comes from its own repository](2026-10-22-wire-comes-from-its-own-repository.md) and [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md): they stay their own repositories, and are also checked out inside Groundwork.

**Decision:** `libraries/wire` and `libraries/cellar` are git submodules of their public repositories, each at the tag `deps.edn` names. `deps.edn` keeps taking them by tag and sha; the alias `:local-libraries` takes them from those folders instead, to change one and Groundwork together. A bump is the tag in `deps.edn` and the submodule moved to the same commit, in one Groundwork commit.

**Why:** the libraries Groundwork stands on are in its tree, readable and changeable next to the code that uses them, while each stays takeable alone. An app takes Groundwork from git, and git deps don't fetch submodules, so `deps.edn` can't point at the folders.

**The price:** two pins to keep together, the tag in `deps.edn` and the submodule's commit. A change to wire or cellar is still a commit and a tag in its repository, then a bump here.

**Instead of:**
- plain folders, the repositories archived: one commit for everything, but Groundwork is private, so wire and cellar could no longer be taken alone
- plain folders, copied out to the repositories on release: two copies that drift, and a publish script
