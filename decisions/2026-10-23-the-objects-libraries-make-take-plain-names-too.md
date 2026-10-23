# The objects libraries make take plain names too

> Follows [Each library's plain name](2026-10-23-each-librarys-plain-name.md); made with [the one pass](2026-10-23-the-plain-names-ship-in-one-pass.md).

**Decision:** an object a library makes is named by what it is, not by the library's old word:

| library | today | becomes |
|---|---|---|
| groundwork-file-storage | an attic: `make-attic`, `stop-attic!` | a file storage: `make-file-storage`, `stop-file-storage!` |
| groundwork-email | a postbox: `make-postbox` | a mailer: `make-mailer` |
| groundwork-logs | a logbook: `make-logbook` | a logger: `make-logger`; the function it gives is still the log |
| groundwork-shaped-json | a wire: `make-wire-from-settings`, every library's `:wire` option | a shaper: `make-shaper-from-settings`, the `:shaper` option |
| groundwork-live-updates | `put-doorbell-on-tables`, `check-doorbell-of-tables`, a table map's `:doorbell` | `put-live-updates-on-tables`, `check-live-updates-of-tables`, `:live-updates` |
| groundwork-metrics | a meter | kept: a meter |

**Why:** with the libraries renamed, an attic, a postbox, a logbook and a wire would be words that name nothing else any more. A mailer, a logger and a shaper say what the thing does; "make-email" would read like writing one email.

**The price:** every options map that hands a library the app's parts changes (`{:log :meter :wire}` is `{:log :meter :shaper}`), and so does every local of those names.

**Instead of:** keeping the old words for the objects: fewer changes, but two words for one thing; `email-sender` (longer than mailer), `json-shaper` (long in every options map).
