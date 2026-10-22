# A namespace names its stylesheets

> **Changed in part** by [A def names its stylesheet, as a query names its .sql file](browser/styles/2026-10-22-a-def-names-its-stylesheet-as-a-query-names-its-sql.md) (2026-10-22): a stylesheet is named by `(styles/use-stylesheet "…")` in the namespace, not in its ns metadata.

> **Changed in part** by [Built files are gzipped, not minified](server/browser-build/2026-10-22-built-files-are-gzipped-not-minified.md) (2026-10-22): no lightningcss, no `:targets`; the bundle is gzipped.

> Replaces [A class is a namespaced keyword](2026-10-15-a-class-is-a-namespaced-keyword.md), [A stylesheet paints only its own room](2026-10-15-a-stylesheet-paints-only-its-own-room.md), [The styles tree](2026-10-15-the-styles-tree.md), [The styles tree's order is cascade layers](2026-10-13-the-styles-trees-order-is-cascade-layers.md) and [A page loads the stylesheets its code uses](2026-10-15-a-page-loads-the-stylesheets-its-code-uses.md). Lifts the freeze for styles ([Groundwork is frozen](2026-10-15-groundwork-is-frozen.md)).

**Decision:** a namespace says which stylesheets it needs in its own metadata, `(ns app.flex.job-card {:css ["job-card.css"]})`, with paths next to the namespace's file. Classes are plain text, stylesheets plain CSS.

What Groundwork does with it:

- **Bundles:** one stylesheet bundle per browser module, gathered from the namespaces in that module, in the order they are found.
- **Loading:** the page links the bundles of the modules it starts with. A module loaded on demand brings its bundle, and its code runs only once the bundle is in, so nothing shows unstyled.
- **Development:** saving a stylesheet rebuilds its bundles and updates the open page.
- **Release:** every bundle is minified by lightningcss for the browsers the app names in a required `:targets` setting (`"safari >= 16.4, chrome >= 119"`), so nesting is flattened and vendor prefixes are right for them; and a stylesheet it can't parse stops the build, naming the file. Bundles get a content fingerprint in their name; the page reads the names from the build's manifest.
- **Checks at every build:** every stylesheet under the sources is named by some namespace, and every named file exists. Otherwise the build stops with the list.

The app's own taste in CSS (design tokens, no raw colours, no `!important`) stays the app's to check.

**Why:** the stricter method (a class as a keyword, one stylesheet per class namespace, a tree apart from the code) asked an existing app to rewrite every class and move every stylesheet before it could use any of it, so no app used it. Naming stylesheets in the namespace keeps the style next to the code that uses it, needs nothing rewritten, and still lets the build work out exactly what each module loads.

**The price:**
- Nothing checks that a class used in code exists in a stylesheet, or that a stylesheet styles only its own elements.
- A namespace that forgets to name a stylesheet it relies on gets it only when another namespace in the same module names it.
- lightningcss runs through the build's node tools.

**Instead of:**
- a default browser floor inside Groundwork: an app's floor would be hidden, and a change of the default would move every app
- keeping the stricter method next to this one: two ways to do one thing, and the strict one has no user
- every module's bundle in the first page: every visitor downloads every area's styles
