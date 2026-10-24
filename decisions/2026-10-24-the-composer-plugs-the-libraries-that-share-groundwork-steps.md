# The composer plugs together the libraries that share groundwork-steps; they don't require each other

> The user's decision, 2026-10-24 (groundwork v4.0.0). Adds to [A library holds one idea and knows nothing of who uses it](2026-10-22-a-library-holds-one-idea-and-knows-nothing-of-who-uses-it.md).

**Decision:** groundwork-bridge, groundwork-access, groundwork-live-updates and groundwork-screens don't require each other. What they share is a new library, [groundwork-steps](https://github.com/p4ulcristian/groundwork-steps) (`.cljc`, no dependencies): the step's shape `{:name :enter}`, `run-steps-until-answered`, `add-answer-to-context`, `does-context-have-answer?`, `refuse`, `make-refusal`, `is-error-answer?`, `refusals`, and the context's and the refusal's keys, `:groundwork-steps/…` (`request`, `message-name`, `message-data`, `log`, `shaper`, `answer`, `notes`, `refused`, `data`, `request-id`; the reasons `too-many`, `failed`, `invalid`, `no-answer`, `unfit`). The lower layers (groundwork-logs, groundwork-metrics, groundwork-settings, groundwork-shaped-json, groundwork-postgres, groundwork-languages) may still be required, and groundwork-screens keeps groundwork-live-updates's question store, one layer on the one below.

groundwork is the one place that plugs them together:

- groundwork-bridge's runner answers groundwork-access's steps: the handlers made ready by groundwork-access go to `make-routes-for-messages` and `answer-message`, as before. groundwork-access's leak test is handed the runner, `:answer-message`; groundwork's `make-leak-test-answerer` makes it from the running app, groundwork-bridge's `answer-message` with `:no-rate-limits`, the app's shaper, log and `:metric-buckets`, and a meter of its own per table of handlers, never served (the app's meter has bridge's metrics declared for the app's whole table, and the leak test asks with whichever handlers it checks).
- groundwork-live-updates tells groundwork-bridge's sse lines: `start-listening!`'s `:push!` is `#(bridge/send-message-on-sse-line! %1 %2 nil)`, its `:find-line-key` the line's `:groundwork-bridge/channel`; bridge's `:on-sse-open` and `:on-sse-close` add and remove the line, as before.
- groundwork-screens sends by groundwork-bridge's `send-message!` (`:send-write!`, the store's send), as before; it follows the address bar itself now (groundwork-bridge's browser routing moved to it), and opens no line.

For an app the names change: a step reads `:groundwork-steps/message-data` and `:groundwork-steps/log`, answers with `groundwork-steps.api/add-answer-to-context` and refuses with `groundwork-steps.api/refuse`; a refusal is `{:groundwork-steps/refused … :groundwork-steps/data …}`; the catalogs hold the texts of `groundwork-steps/too-many`, `failed`, `invalid`, `no-answer` and `unfit`; `groundwork-bridge.api` no longer has the step functions, `refusals`, `is-error-answer?` or the browser's routing; `groundwork-screens.api` has `make-address-of-page`. So groundwork is v4.0.0.

**Why:** each of the three above the wire required groundwork-bridge only for a few shapes and one or two functions, and so carried the whole wire and its releases (every bridge release cascaded through all three). With the shapes in one small library and the few functions handed in, each library holds its one idea and knows nothing of its siblings, and how they run together is written once, here, where the end-to-end test proves it.

**Instead of:**
- the siblings kept requiring groundwork-bridge for the shapes: the tie this removes
- the shapes copied into each library as plain keywords: no single definition, and a change goes wrong unseen
- the libraries' tests plugging their siblings in (groundwork-bridge as a test-only dependency): the plugging would be in five places, not one; each library's tests stand in for the others with a runner or lines of their own, and groundwork's tests run the real ones together
- an app plugging groundwork-bridge into groundwork-access's leak test itself: one more place that knows how the two meet; `make-leak-test-answerer` keeps it here
