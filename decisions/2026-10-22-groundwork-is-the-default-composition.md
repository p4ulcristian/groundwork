# Groundwork is the default composition

> Replaces [A Groundwork app uses the libraries through Groundwork](2026-10-22-a-groundwork-app-uses-the-libraries-through-groundwork.md). Changes in part [Declared, through one door](2026-10-14-declared-through-one-door.md) and [Groundwork is an opinionated framework](2026-10-12-groundwork-is-an-opinionated-framework.md).

**Decision:** the `ground-*` libraries are the base, and `groundwork` is their default composition: the recommended stack, given as plain data (`(gw/defaults settings)`: the steps of each kind of work, in order, and how the libraries are wired). An app starts from it, can print it, and can add its own steps with plain Clojure; its own steps may use any `ground-*` library. What keeps every app safe is a check, not a wall: the start refuses work whose steps lack the required ones (the access check). The `ground-*` libraries never break: they only add, and a changed meaning gets a new name.

**Why:** the opinions stay (an app that takes the defaults decides nothing), but they are visible and open, in the way Clojure's libraries are (ring-defaults: one call, a map you can read). Libraries that never break are ones an app can keep depending on.

**The price:** safety rests on what the start checks; an app's own step runs inside the request with everything a step can do. A library can't fix a mistake by changing its meaning; it adds a new name and leaves the old one.

**Instead of:**
- one door, the libraries hidden behind `groundwork`: safest by construction, but the steps can't be seen or extended, the framework Hickey warns about
- only libraries, each app wiring them itself: the purest, but every app rebuilds the wiring and the leak guarantee is gone
