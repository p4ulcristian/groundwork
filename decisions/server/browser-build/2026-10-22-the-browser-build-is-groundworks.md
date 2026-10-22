# The browser build is Groundwork's: watched in development, built once by the deploy

> Builds on [Groundwork starts the browser and owns the build](../../2026-10-12-groundwork-starts-the-browser-and-owns-the-build.md).

**Decision:**

- **A library of its own**, groundwork.server.browser-build: shadow-cljs, given a build Groundwork writes as data (`make-build`: one module, `main`, files in `<files-folder>/js`, served at `/js`). The app gives only its entry, `:browser-entry 'app.browser.main/start!`. No `shadow-cljs.edn` in the app.
- **Development:** `start-app!` starts the watcher in the same JVM when the settings say `:browser-build :watch`: compiled now, again on every save, the new code swapped into open tabs. The page loads `/js/main.js`.
- **Production:** the deploy runs `clojure -X:browser-release …` once, before the app starts: optimized, each file's name with a hash of its content, and `js/manifest.edn` listing them. The app starts with `:browser-build :built`; the page shell lists the files the manifest names, and refuses to start without one.
- **Two locks keep the watcher out of production:** the setting, and the dependencies: shadow-cljs is only in the `:browser-build` and `:browser-release` aliases, and main loads the library only when it watches (`requiring-resolve`).
- **Files** are served from a folder on disk (`:page :files-folder`): a hashed name is cached for a year (`immutable`), any other file asked again each time. The `:page :scripts` setting is gone.

**Why:** one command and one REPL in development; in production the page and the files can't disagree (the shell reads the build's own list), a new version is never served from a stale cache, and the running app carries no compiler.

**The price:** the deploy has one more step; an app with unusual build needs (several modules, npm packages) needs this library to grow a setting.

**Instead of:**
- shadow-cljs as its own process next to the server in development: the usual way, but two things to start, and their settings kept equal by hand
- a `shadow-cljs.edn` per app: build settings every app repeats, and the page shell would have to read them
- `:scripts` written in the settings: the page could name a file the build didn't write
