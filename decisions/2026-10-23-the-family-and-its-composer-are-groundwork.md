# The family and its composer are groundwork

> Changes in part [Groundwork is renamed house, and the group of libraries is manor](2026-10-22-groundwork-is-renamed-house-and-the-group-is-manor.md): both names are groundwork again.

**Decision:** one name, groundwork, for the family of libraries and for the repository that puts them together.

- **groundwork** is the family: every library (wire, cellar, attic, backroom, showroom, parlor, mailroom, postbox, doorman, doorbell, logbook, meter, study, dictionary, safe) and the composer carry the GitHub topic `groundwork` (`user:p4ulcristian topic:groundwork`); `manor` is gone. The libraries keep their own names.
- **groundwork** is the composer, the repository that was house: github.com/p4ulcristian/groundwork (GitHub forwards `p4ulcristian/house`). The folders: the family in `~/Work/apps/groundwork`, the composer in `~/Work/apps/groundwork/groundwork`.

What says groundwork now, where it said house:

- the tree: `source-code/groundwork/{server,browser}/main`, `test-code/groundwork/…`, the sample app in `test-code/groundwork_test/…`; the namespaces `groundwork.server.main.*`, `groundwork.browser.main.api`, `groundwork-test.*`; the `.sql` files' paths, `groundwork/server/main/…`
- the public name `house-tables` is `groundwork-tables`; the keywords `:house/…` and `:house.server.main/…` are `:groundwork/…` and `:groundwork.server.main/…` (error types, log events, the handler `:groundwork/browser-error`, the steps and the background job)
- the metrics: `groundwork_stage`, `groundwork_ready_checks_total`, `groundwork_build_info`
- what the browser sees: the version's message `:groundwork/version`, the settings tag `<script id="groundwork-settings">`, the sessionStorage key `groundwork-reloaded-for`; the paths in the example settings, `/groundwork/http`, `/groundwork/errors`, `/groundwork/visitor`, `/groundwork/files`
- Postgres: the one-time keys are in the schema `groundwork` (`groundwork.run_once_keys`)
- the tests' environment: `GROUNDWORK_SQL_TEST_PORT`, `GROUNDWORK_SQL_TEST_USER`, `GROUNDWORK_E2E_BROWSER`, `GROUNDWORK_S3_TEST_ENDPOINT`, `_ACCESS_KEY`, `_SECRET_KEY`
- the npm package name in package.json

No running app has the house names: Iron Rainbow takes the old v0.4/v0.5 line from `main`, which never had them. Decision files written before today keep their words; indexes keep their titles.

**Why:** groundwork is the name the owner thinks in. A family named after the artifact that brings it together is common: reitit, ring and pedestal each name both the family and the one dependency that brings the parts. The double meaning that house and manor were made to remove a day earlier costs less than two names nobody thinks in.

**Instead of:**
- **manor + house**, the names given the day before
- **groundwork + house**: groundwork for the family, house kept for the composer
