# A def names its stylesheet, as a query names its .sql file

> Changes in part [A namespace names its stylesheets](../../2026-10-17-a-namespace-names-its-stylesheets.md): a stylesheet is named by a call in the namespace, not in the ns form's metadata; the rest (plain CSS next to the code, plain-text classes, one bundle in the namespaces' order, the checks) stays.

**Decision:**

- A namespace names each stylesheet it needs the way a query names its SQL: `(def order-card-styles (styles/use-stylesheet "app/orders/order_card.css"))`, the path on the classpath, the file next to the namespace. Classes stay plain text, the CSS plain CSS.
- `use-stylesheet` is a macro: it runs while compiling, where files can be read. A missing file stops the build at that line, and the namespace's stylesheets are recorded in its own compiled data (so shadow-cljs's cache keeps them, and a recompile starts clean).
- After every compile, groundwork.server.browser-build gathers them into one bundle, in the order the namespaces are found, each whole and as written: `css/main.css` while watching (saving a stylesheet re-bundles it and shadow-cljs swaps it into the open page), `css/main.<hash>.css` with `css/manifest.edn` in a release. The page shell links it; the `:page :stylesheets` setting is gone.
- The check: every `.css` beside the build's namespaces must be named by one. A release stops on any that isn't; while watching they are printed.
- Not yet: minifying the bundle for the app's browsers (lightningcss, `:targets`) and a bundle per module (there is one module). Each is its own decision.

**Why:** one pattern for every file a namespace needs, SQL or CSS: a def names it, and a wrong name fails at that line. The ns-metadata form failed nowhere: a typo in it was found only by the "named file exists" check, apart from the code.

**The price:** a Groundwork macro (the metadata needed none); still nothing checks that a class used in code exists in a stylesheet.

**Instead of:** `(ns app.orders.order-card {:css ["order_card.css"]})`: no call, but a second way to name a file, and the failure far from the line.
