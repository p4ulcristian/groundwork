# The styles tree's order is cascade layers

> **Replaced** by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md).

> Adds to [The styles tree](2026-10-15-the-styles-tree.md) and [A page loads the stylesheets its code uses](2026-10-15-a-page-loads-the-stylesheets-its-code-uses.md).

**Decision:** the four folders of the styles tree are four cascade layers, declared once, first, in the base: `base`, `vendor`, `looks`, `areas`. The build puts each stylesheet, whole and as written, inside its folder's layer. Which of two rules wins is then decided by the folder they are in, never by which bundle arrived first.

**Why:** the tree's order was the load order, and that held only while everything loaded up front. A general look used only by a screen loaded on demand arrives after the areas' styles of the first bundle, so on an element that carries both, the look won where the area should. Layers make the order a declared fact: an area's rule beats a look's whenever it loads.

**The price:**
- Browsers from before March 2022 (Chrome 99, Firefox 97, Safari 15.4) don't know layers and skip everything inside them: such a browser shows the page unstyled.
- A rule outside every layer beats all four. Styles a library injects by itself while running are such rules; where one must be overridden, the library's wrapper stylesheet says `!important`, which is the one place it is allowed.
- An area's rule now beats a look's even when the look's selector is more specific.

**Instead of:**
- always loading every look in the first bundle: the order holds, and every visitor downloads looks only one late screen uses
- leaving it to load order
