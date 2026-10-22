# groundwork.server.browser-build

The browser's code, built by shadow-cljs from a build Groundwork writes as
data (`make-build`); the app gives only its entry, the function the page
runs. Runs on the server's side, but only to build: production has no
shadow-cljs.

- **Development:** groundwork.server.main starts the watcher in the same JVM
  when the settings say `:browser-build :watch`: compiled now, again on every
  save, the new code swapped into open tabs. The page loads `/js/main.js`.
- **The deploy:** `clojure -X:browser-release :entry app.browser.main/start! :files-folder '"target/public"'`
  compiles once, optimized, into `js/main.<hash>.js` and `js/manifest.edn`;
  the app starts with `:browser-build :built`, and the page shell lists the
  files the manifest names. Hashed files are cached for a year.

API: `groundwork.server.browser-build.api` (`make-build`, `start-watching!`,
`stop-watching!`, `build-for-release!`). Decisions:
[`decisions/server/browser-build`](../../../../decisions/server/browser-build/README.md).

Its test release-builds an entry that uses every browser library.
