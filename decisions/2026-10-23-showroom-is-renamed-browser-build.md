# showroom is renamed browser-build

> Changes in part [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md): the library's name, repository and keys.

**Decision:** groundwork takes browser-build, the library named showroom until now, from github.com/p4ulcristian/browser-build (`io.github.p4ulcristian/browser-build`, v0.4.3), in the `:browser-build` and `:browser-release` aliases only, through `browser-build.api` only; `libraries.edn` and `libraries_test.clj` say so. Every key and log event it gives is named `browser-build` (`:browser-build/bad-options`, `:browser-build/unnamed-stylesheets`, `:browser-build/bundle-failed`, …): [browser-build's decision](https://github.com/p4ulcristian/browser-build/blob/main/decisions/2026-10-23-showroom-is-renamed-browser-build.md). The app's settings do not change: `:browser-build` already named the build.

**Why:** the name says what the library does: it builds what the browser loads, the ClojureScript and the stylesheets. showroom read as a library that shows things.

**The price:** an app's requires (`[browser-build.api :as styles]`), its deps.edn aliases and any code matching `:showroom/…` keys change at once.

**Instead of:** keeping showroom, or a house word like workshop; the reasons are in browser-build's decision.
