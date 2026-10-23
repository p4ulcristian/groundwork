# The browser build's npm packages are real dependencies; doorman owns the shape of "what may I do?"

> The user's decisions, 2026-10-23, on two loose ends. Takes showroom v0.4.1 (a release checks its npm packages are the app's own), doorman v0.6.0 (the shape on both sides) and parlor v0.6.0 ("can I?" checks the answer by the shape it is handed). Changes in part [The browser start starts parlor](2026-10-23-the-browser-start-starts-parlor.md), whose price was "the answer shape of `:doorman/my-actions` is written twice".

**Decision:**

- **`process` is one of house's npm `dependencies`**, beside big.js, react and react-dom (`package.json`). react's code requires `process`, which the browser build bundles; it was on this machine only because the shadow-cljs npm package, a development dependency, brings it, so a deploy that installs without development packages (`npm ci --omit=dev`) couldn't build. Nothing defines `process.env` by hand. showroom's release build now checks, before compiling, that every npm package it takes code from is installed as the app's own dependency and stops, naming the package and the fix, when one isn't; an app writes the same four in its own `package.json`.
- **doorman owns the shape of `:doorman/my-actions`'s answer.** house's browser start puts `doorman/my-actions-answer-shape` (doorman.api's browser half) into the browser's shapes by name, the way every message's shapes travel, and hands it to parlor as `:actions-answer-shape`, with the app's wire, which "can I?" checks the answer by. parlor's copy is gone. `libraries.edn`: a browser library may use `doorman.api` too, for that shape.

**Why:** a deploy must not depend on what a development tool happened to install; the check finds it on the developer's machine, before the deploy does. One shape, written where the answer is made, can't drift from a copy.

**The price:** `process` is listed though no code of the app's names it. The browser build reads doorman's `.cljc` shape.

**Instead of:**
- defining `process.env.NODE_ENV` in the build: fixes one package's one use
- a test in house checking that doorman's and parlor's shapes match: two copies still
