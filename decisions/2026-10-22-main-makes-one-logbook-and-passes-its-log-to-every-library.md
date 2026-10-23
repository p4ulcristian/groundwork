# main makes one logbook and passes its log to every library

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): logbook is groundwork-logs; main makes one logger, from the `:logs` settings.

> Changes in part [main makes one wire and passes it to every library](2026-10-22-main-makes-one-wire-and-passes-it-to-every-library.md) and [Main answers health checks](server/main/2026-10-22-main-answers-health-checks.md) (the database's error went to `System.Logger` `house`): one settings part more, house takes logbook v0.1.0, cellar v0.2.0, backroom v0.3.0, mailroom v0.3.0, doorman v0.3.0, doorbell v0.3.0 and showroom v0.2.0.

**Decision:** logbook makes a logbook from settings, and a library is handed one function, `(log level event data)` (logbook's "A log call is level, event, data"). house makes the app's one logbook and passes its log to every library that writes a line, as a required option; no library makes its own or decides how a line is written ("the app passes its log", each library's decision).

- **`:logbook`**, required in house's settings: logbook's settings, `logbook.api/settings-shape`, every key written out:

  ```clojure
  :logbook {:lowest-level :info      ; development :debug
            :format       :json      ; development :readable
            :errors-kept  1000       ; the newest :error lines, for the errors route
            :write-to     :stdout}
  ```

- `start-app!` checks the settings, makes the logbook once, `(logbook/make-logbook (:logbook settings))`, and its log, and passes the log on:

  | library | where | what it writes |
  |---|---|---|
  | cellar | `migrate!`'s options `:log` (development's migrate) | each planned statement, `:cellar/planned` |
  | doorbell | `start-listening!`'s options `{:log log}` | cellar's listener: a break, a failure telling the lines |
  | backroom | `start-workers!`'s options `:log` | each run gets a log with its background job's id and kind; `:backroom/failed`, `:backroom/gave-up`, … |
  | showroom | `start-watching!`'s options `:log`, while watching | an unnamed stylesheet, a failed rebundle |
  | mailroom | `make-routes-for-messages`'s options `:log`, `start-server!`'s `{:log log}` | each message's steps get a log with a new request id (`:mailroom/log`); a handler's failure, http-kit's errors and warnings |
  | doorman | nothing passed: its find-session step adds `{:person-id …}` to the message's log | every later step's line says who asked |
  | house | the health checks; `start-app!` and `stop-app!` | `:house/database-not-ready` (warn), `:house/started` and `:house/stopping` (info) |

  The running app holds both, `:logbook` and `:log`, for the app's own lines outside a message.
- `migrate-app!`, the deploy's, runs in its own process: it makes a logbook from the same `:logbook` settings for the plan's lines.
- The `:browser-build {:watch …}` settings leave out `:log`, as they leave out `:entry` and `:files-folder`: house passes its own.

One message's lines, then, carry its request id and the person whoever wrote them: mailroom's handler failure, cellar's warning through doorbell's notes, the app's own step:

    {"time":"…","level":"error","event":"mailroom/handler-failed",
     "context":{"request-id":"7f3a…","person-id":"41b2…"},"data":{"mailroom/message-name":"orders/broken"},"error":{…}}

**Why:** as with the wire: one value made from the app's settings is in effect everywhere, and the settings show it (the lowest level, the format, how many errors are kept). The context reaches every line because each library hands down the log it was given.

**The price:** the settings grow by one part (4 keys). Every library's call in `start-app!` writes its log.

**Instead of:**
- each library logging through `System.Logger` or `println`, as before: lines in no shape a shipper reads, without the request, and where they go set by the JVM's setup, not the app's settings
- a log per library from its own settings: several lowest levels and formats in one app
