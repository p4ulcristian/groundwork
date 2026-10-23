# Pages for other sites, visitors, and servers

> **Changed in part** by [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22): the shell's `X-Frame-Options` is the `:page` setting `:frame-options` (`"DENY"`, `"SAMEORIGIN"` or `:none`), not fixed.

> **Changed in part** by doorman's [Sessions for visitors, and none for servers](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-sessions-for-visitors-and-none-for-servers.md) (2026-10-22): visitor sessions, and reading no session and skipping the Origin check for a server-to-server route, moved there whole. What stays here is the routes.

**Decision:** a route says what kind of request it answers beyond a signed-in person on the app's own pages:

- **Embeddable pages.** A route can declare `:embeddable`: any https site, or a list of origins, may show it in a frame. Only there Groundwork names those sites in `frame-ancestors` and sends no `X-Frame-Options`; every other answer stays unframeable.
- **Server-to-server routes.** A route can declare `:server-to-server`: another server calls it (a payment provider's webhook); doorman reads no session for it and skips the Origin check, and the handler authenticates the call itself. Every other route is a browser route.

**Why:** an app's page can live on its customers' own websites, and providers call the server directly. Each is declared on its route, so the default stays strict.

**The price:** in another site's frame the browser doesn't send the session cookie, so a flow that needs it (sign-in, checkout) opens on the app's own site. A server-to-server route has no protection from Groundwork; its handler must check the call.

**Instead of:** allowing every page in frames (any site could frame the app's pages to trick clicks), or turning the Origin check off for webhooks app-wide.
