# showroom comes from its own repository

> Changes in part [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md), [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md), [Nine libraries, by the questions an app asks](2026-10-22-nine-libraries-by-the-questions-an-app-asks.md) and [Groundwork starts the browser and owns the build](2026-10-12-groundwork-starts-the-browser-and-owns-the-build.md): the browser build and styles libraries are no longer folders in the tree, and their namespace, `showroom`, says no side.

**Decision:** the browser build (`groundwork.server.browser-build`) and the styles library (`groundwork.browser.styles`, the `use-stylesheet` macro) are together one library of their own named showroom: the public repository github.com/p4ulcristian/showroom, MIT. Its one public namespace is `showroom.api`: the `.clj` half holds `make-build`, `start-watching!`, `stop-watching!`, `build-for-release!` and the macro, the `.cljs` half only requires the macros, so the browser's code writes `(styles/use-stylesheet "app/orders/order_card.css")` through `showroom.api`. Its part is `showroom.stylesheets`. Its tests and its own decisions ("The browser build is Groundwork's", "Built files are gzipped, not minified", "A def names its stylesheet", and the styles decisions before them: "A namespace names its stylesheets" and the five it replaced) live in its repository.

- **shadow-cljs comes with showroom**, which names it in its own `deps.edn`. Groundwork takes showroom by tag and sha (`io.github.p4ulcristian/showroom`, v0.1.0) only in the `:browser-build`, `:browser-release` and `:test` aliases, never in `:deps`, and names shadow-cljs nowhere itself. main loads `showroom.api` only when it watches (`requiring-resolve`), and `libraries_test.clj` fails if a server namespace requires it. So production's classpath has neither showroom nor shadow-cljs. The release build is `clojure -X:browser-release` with `showroom.api/build-for-release!`.
- `libraries.edn` no longer lists `:browser-build` or `:styles`; a browser namespace may use showroom through `showroom.api` only, which `libraries_test.clj` checks.
- It knows nothing of Groundwork: the build id is `:showroom`, the compiler data key `:showroom/stylesheets`, what it throws is typed `:showroom/…`. What the build does is unchanged.
- main's settings key stays `:browser-build` (`:watch` or `:built`): it says how main runs the page, not which library builds it.
- Groundwork keeps one browser build test (`test-code/groundwork/browser/build_check_test.clj`): showroom release-builds an entry that uses every browser library and names a stylesheet.

**Why:** the build and the stylesheets it bundles are one idea, what the browser is shown, and they needed nothing of Groundwork. One library, not two: the macro's only reader is the build, and an app takes them together. Its own repository, with a `deps.edn` naming only shadow-cljs, proves it needs nothing more, and lets an app take it alone. shadow-cljs pinned by showroom is pinned once, beside the code that calls it. The name: the showroom is the room customers see, opposite backroom, where the work is done out of sight; showroom builds what the browser shows.

**The price:** a change to showroom is a commit and a tag in another repository, then a bump here, in three aliases. An app has to keep showroom out of its own `:deps`. Groundwork's tests no longer run showroom's (the missing and unnamed stylesheet checks are there).

**Instead of:**
- the two libraries as folders in Groundwork: no app can take them alone, and nothing proves their deps
- two libraries of their own, the build and the styles: the macro means nothing without the build
- the names workshop, facade and browser-build: showroom was chosen over them
