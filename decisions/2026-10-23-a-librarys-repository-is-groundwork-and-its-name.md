# A library's repository is groundwork- and its name

> Follows [Each library's plain name](2026-10-23-each-librarys-plain-name.md).

**Decision:** each library's repository, deps coordinate and folder carry `groundwork-` before its name: github.com/p4ulcristian/groundwork-postgres, `io.github.p4ulcristian/groundwork-postgres`, `~/Work/apps/groundwork/groundwork-postgres`. The two kept names too (`groundwork-bridge`, `groundwork-browser-build`), so every library reads alike. The composer stays `groundwork`. Each is still a library of its own, with its own tags.

**Why:** the plain words alone (`postgres`, `email`, `settings`) are too generic for a repository or a coordinate; with `groundwork-` each says whose it is, and its namespaces start with the same name ([decision](2026-10-23-a-librarys-namespaces-start-with-its-repository-name.md)): `groundwork-postgres.api`.

**The price:** longer repository names and coordinates in every app's deps.edn; GitHub forwards the old addresses, so an app that takes a library by its old address still gets it until it moves.

**Instead of:** the plain words alone (`p4ulcristian/postgres`): short, but generic on GitHub and as a coordinate.
