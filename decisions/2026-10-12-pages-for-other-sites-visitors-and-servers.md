# Pages for other sites, visitors, and servers

**Decision:** three kinds of request beyond a signed-in person on the app's own pages:

- **Embeddable pages.** A route can declare `:embeddable`: any https site, or a list of origins, may show it in a frame. Only there Groundwork names those sites in `frame-ancestors` and sends no `X-Frame-Options`; every other answer stays unframeable.
- **Visitor sessions.** Someone not signed in gets a session the first time the app keeps something for them (a cart, uploaded designs), not on every request. It ends after `:visitor-hours` unseen, a required setting; each visit pushes the end out, at most once an hour. At sign-in its data moves into the signed-in session, under a new key, so a key planted before the login is worthless after it.
- **Server-to-server routes.** A route can declare `:server-to-server`: another server calls it (a payment provider's webhook). No session is read or made and the Origin check doesn't apply; the handler authenticates the call itself, like a signature. Every other route is a browser route and keeps the Origin check.

**Why:** an app's page can live on its customers' own websites, people fill a cart before they sign in, and providers call the server directly. Each one is declared on its route, so the default stays strict: unframeable, no session for nobody, every changing request from the app's own pages.

**The price:** in another site's frame the browser doesn't send the session cookie, so a flow that needs it (sign-in, checkout) opens on the app's own site. A server-to-server route has no protection from Groundwork; its handler must check the call.

**Instead of:** allowing every page in frames (any site could frame the app's pages to trick clicks), a session for every visitor (a row per crawler hit), or turning the Origin check off for webhooks app-wide.
