# The REPL starts last and stops first; house brings its tools

> **Changed in part** by [A token call arrives where a browser's does; the composer names no person](2026-10-23-a-token-call-arrives-where-a-browsers-does.md) (2026-10-23, built on tidy-up, not released): `ask-as-person`'s stand-in for the session step is `doorman.repl`'s `make-step-asking-as`, and it answers by `answer-as`'s own path with no request.

> The user's decision, 2026-10-23 (study v0.1.1). Takes study's [The tools are the app's functions, in study.tools](https://github.com/p4ulcristian/study/blob/main/decisions/2026-10-23-the-tools-are-the-apps-functions-in-study-tools.md) and [REPLs by environment](https://github.com/p4ulcristian/study/blob/main/decisions/2026-10-23-repls-by-environment.md); changes in part [REPLs by environment](2026-10-12-repls-by-environment.md): the REPL is study's, started by house.

**Decision:**

- **The `:study` settings are study's** (`study/settings-shape`): `:development`, `:ssh-only`, or `:off` with the emergency switch, every key required.
- **`start-app!` starts the REPL last**, once the server listens, and **`stop-app!` stops it first**; the running app holds it, `:study`. A start that fails before it never opens one.
- **house's tools** (`house.server.main.tools`), in `study.tools` while the REPL is open:
  - `(ask-as-person message asker)`: the message answered as the asker, through every check a browser's message gets (its params' shape, doorman's access, the gates, the answer's shape, a write keyed to run once when a key is sent), by mailroom's `answer-message`, no rate limits. The app's handlers are made ready as at the start, with a stand-in for doorman's session step that puts `:doorman/person-id`, `:doorman/visitor-id` and `:doorman/session-flags` from the asker in the context, and the person or visitor in the message's log. The params are data with their types, turned into the browser's JSON by each handler's shape.
  - `(why-refused asker handler-name params)`: doorman's `explain-refusal`, with the app's pool, handlers, tables and scope kinds.
  - `(explain-setting path)`: `{:path :shape :doc}`, the shape at the path in house's `settings-shape` (`malli.util/get-in`) and the docstring of the shape var of the part the path starts in (each library's own, or house's).
  - The asker is `{:person-id :visitor-id :session-flags}`, every key required, the same as doorman's `explain-refusal` takes, for both tools that ask as someone.
- **The tools close over the running app's parts**, no atom: the REPL lives exactly as long as the app that opened it. A developer restarting the app does it from their own JVM REPL (`clj -A:test`), not from study's, which the stop closes.
- `.nrepl-port` is ignored by git.

**Why:** the REPL reaches the app only once it is whole, and nothing evaluates into an app while it shuts down. One asker shape for both tools is one thing to know; it says the visitor and the flags, which a person id alone can't.

**The price:** `(stop-app! …)` typed into study's own REPL closes that REPL. The tools are house's code, tested by `house.server.main.api-test` over a real nREPL connection.

**Instead of:**
- the running app in an atom the tools read: worth it only if the REPL outlived the app; here it doesn't
- `(ask-as-person message person-id)`, study's sketch: no way to ask as a visitor or with a flag
- the tools in study: study would require doorman and mailroom
