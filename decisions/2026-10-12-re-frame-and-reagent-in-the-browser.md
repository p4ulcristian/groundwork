# re-frame and Reagent in the browser

> **Changed in part** by [A namespace names its stylesheets](https://github.com/p4ulcristian/showroom/blob/main/decisions/2026-10-17-a-namespace-names-its-stylesheets.md) (2026-10-17): Groundwork bundles and loads the stylesheets an app's namespaces name, under a `:styles` setting; the CSS itself stays the app's.

**Decision:** Groundwork's browser side is re-frame for state and Reagent for drawing. Groundwork decides the plumbing there: the store of server answers, how a screen says what it needs (entering a route, opening a panel), sending writes, the "can I?" subscription, and browser routing (reitit, as on the server). Its own events and subscriptions are named under `:groundwork/…`.

Everything the person sees and touches stays the app's own code, written freely: components, styles, animations, its own events and subscriptions, other libraries (a 3D canvas, drag and drop). Groundwork has no settings for any of it; the page shell only loads the app's script and styles. Where the app's screens touch data, they use Groundwork's building blocks.

**Why:** the store is re-frame already, so leaving the choice open would make every app wire it in again; one way to build a screen's data side keeps apps alike where it matters and free where it doesn't. re-frame (1.4.7) and Reagent (2.0.1) are both maintained in 2026 and the most used pair in ClojureScript.

**The price:** Groundwork follows React through Reagent. re-frame's one state map needs discipline in a large app; the store being its own section helps.

**Instead of:** leaving the browser side to each app; Replicant (pure data, no React, a small community); UIx (modern React hooks, another style); Electric (another model).
