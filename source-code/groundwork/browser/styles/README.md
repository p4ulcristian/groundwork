# groundwork.browser.styles

How the page looks. A namespace names each stylesheet it needs with
`(def order-card-styles (styles/use-stylesheet "app/orders/order_card.css"))`,
as a query names its `.sql` file; the CSS sits next to the namespace, plain
CSS, and classes are plain text. `use-stylesheet` is a macro (`api.clj`,
run while compiling): a missing file stops the build at that line, and the
build learns what the namespace needs.

groundwork.server.browser-build gathers the named stylesheets into one
bundle, in the order the namespaces are found; the page links it. While
watching, saving a stylesheet updates the open page. Every build checks
that each stylesheet beside the build's namespaces is named by one; a
release build stops when one isn't.

API: `groundwork.browser.styles.api` (`use-stylesheet`). Decisions:
[`decisions/browser/styles`](../../../../decisions/browser/styles/README.md).
