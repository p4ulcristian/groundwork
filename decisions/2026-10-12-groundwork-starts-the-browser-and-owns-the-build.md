# Groundwork starts the browser and owns the build

**Decision:** the app's browser entry makes one call to Groundwork's browser start, with its root screen and its browser routes. Groundwork reads what the page shell put in the page (who is signed in, their grants, the language), sets up re-frame with the store, starts browser routing, opens the `/parquery` websocket, and mounts the root screen into `#app` with Reagent.

Groundwork owns the shadow-cljs conventions: in development, starting the app also starts the watcher; a release compiles with fingerprinted file names and writes the list of them, which the page shell reads, so the page and the build can't disagree. Groundwork declares its own browser libraries, which shadow-cljs installs for the app; the app lists only its own.

**Why:** the build is plumbing, like the server, and fingerprinted names tie it to the page shell. One start in development reloads both sides.

**The price:** an app that needs unusual build settings needs Groundwork to grow a setting.

**Instead of:** each app owning its build config and keeping it in step with the page shell by hand.
