# Renovate keeps the libraries current

**Decision:** Renovate watches Groundwork's libraries (`deps.edn`, `package.json`, the GitHub Actions) with the same rules as Iron Rainbow: minor and patch updates come as pull requests, one for the Clojure libraries and one for npm, and the tests run on each; a major update waits on the Dependency Dashboard until someone ticks it; the npm lockfile is refreshed every Monday morning. The rules are `renovate.json`.

**Why:** a framework every app builds on should not fall behind its libraries without anyone seeing it; on 2026-10-20 nine were behind their newest release. Small updates are cheap to take while they are small, and the tests say whether one breaks something. A major (next.jdbc 1.4, jsonista 1.0) can change behaviour, so it is read and chosen, not merged by habit.

**The price:** update pull requests to review and merge; the Renovate app must be given this repository in GitHub's settings.

**Instead of:** updating by hand when someone notices; Dependabot (no `deps.edn` support); one pull request per library (more pull requests, each easier to blame).
