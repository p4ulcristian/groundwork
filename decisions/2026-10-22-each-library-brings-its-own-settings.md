# Each library brings its own settings

> Changes [Settings are required data](2026-10-12-settings-are-required-data.md) in where the schemas live; the rules (every key required, unknown keys refused, every problem at once) stay.

**Decision:** each `ground-*` library owns the schema of its own part of the settings (`ground-sessions` the `:sessions` map, `ground-sql` the `:db` map), next to its code, as a plain malli value. `groundwork` collects them into the whole settings schema, checks the app's settings against it at once (one list of every problem), runs the checks that span libraries (the sessions' origin against the server, the styles against the browser build), and makes the settings docs from the collected schemas.

**Why:** a library can be used alone, so it carries the check of its own settings. A part's settings sit where the code that reads them is. Adding a library adds its settings by itself. The schemas are values, so collecting them is plain Clojure.

**The price:** small shared shapes (a positive number, an origin) are repeated in the libraries or written with plain malli. A check that spans two libraries can't live in either.

**Instead of:**
- one settings file in `groundwork` for every part, as today (702 lines): one place to read, but a library used alone has nothing checking its settings, and every library change touches `groundwork` too
- each library checking its own settings when it starts, nothing central: simplest, but problems arrive one library at a time and there are no shared docs
