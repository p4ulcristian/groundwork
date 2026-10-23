# Groundwork is renamed house, and the group of libraries is manor

> **Changed in part** by [The family and its composer are groundwork](2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): the family and its composer are both named groundwork again, and the topic is `groundwork`.

> Changes in part [Groundwork's libraries share the GitHub topic groundwork](2026-10-22-groundworks-libraries-share-the-topic-groundwork.md): the topic is `manor`.

**Decision:** two names where Groundwork was one.

- **manor** is the group: the libraries and the repository that puts them together, a big house with its cellar, backroom, showroom, mailroom, doorman, doorbell, and wire. It is the GitHub topic every one of them carries (`user:p4ulcristian topic:manor`), and the word for the set.
- **house** is the part that puts them together, the repository that was Groundwork: `main` on the server and in the browser. The repository is github.com/p4ulcristian/house (GitHub forwards the old address, so an app that takes `git@github.com:p4ulcristian/groundwork.git` by tag still gets it), the folder `~/Work/apps/house`.

What says house now:

- the tree: `source-code/house/{server,browser}/main`, `test-code/house/…`; the namespaces `house.server.main.api`, `house.server.main.page`, `house.server.main.health`, `house.browser.main.api`, the tests' likewise; the `.sql` files' paths, `house/server/main/…`
- the public name `groundwork-tables` is `house-tables`; the error types are `:house.server.main/…` and `:house.browser.main/no-settings`; the server's logger is `"house"`
- what the browser sees: the version's message name is `:house/version` (`"house/version"` on the sse line); the page shell's settings tag is `<script id="house-settings">`; the key that remembers the version a tab reloaded for is `house-reloaded-for` in sessionStorage. No running app had these names: the old v0.5.x line on `main` never had them, and no app runs this line yet. A page opened before a deploy that renamed them would listen for `groundwork/version`, log the new name as a message it has no handler for, and not reload by itself.
- the tests' environment: `HOUSE_SQL_TEST_PORT` and `HOUSE_SQL_TEST_USER`
- Postgres: no object of house's is in a `groundwork` schema; every table and function is in its library's own (cellar, backroom, doorman, doorbell), so nothing in the database is renamed

Decision files written before today's rename keep their words; indexes keep their titles.

**Why:** "Groundwork" had come to mean both the group of libraries and the one repository that puts them together; a sentence like "Groundwork's libraries" could mean either. Two words, one each.

**Instead of:**
- for the group: **estate**, **homestead**, or keeping **groundwork** (the double meaning stays)
- for the part that puts them together: **homestead**, **blueprint**, or keeping **groundwork** (the double meaning stays)
