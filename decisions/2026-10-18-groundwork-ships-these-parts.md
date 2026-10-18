# Groundwork ships these parts

> Replaces [The parts Groundwork ships](2026-10-12-the-parts-groundwork-ships.md).

**Decision:** Groundwork ships these parts. An app gives each its settings and replaces none.

On the server:

- **db:** the Postgres pool, `.sql` files, queries and transactions
- **migrations:** Groundwork's numbered SQL files, then the app's
- **sessions:** who is signed in, and visitors' sessions
- **permissions:** scopes, roles, memberships, extra actions and invitations; the access language and the wall in Postgres; the leak test for the app's own tests
- **endpoints:** the app's reads and writes, their declarations and checks
- **parquery:** the transport, asks over HTTP and bells over a websocket, both at `/parquery`
- **limits:** rate limits for endpoints and routes
- **jobs:** background jobs and schedules, in Postgres
- **web:** the router, with the app's routes next to Groundwork's own, and how every request is handled
- **server:** http-kit, every request on a virtual thread
- **page:** the page shell
- **styles:** the stylesheets the app's namespaces name, bundled per browser module
- **build:** the browser build, through shadow-cljs

In the browser:

- **browser:** the one start call, and the door to routing
- **routing:** an address leads to a screen
- **parquery-store:** the answers, kept by question
- **parquery's browser halves:** asking over HTTP, hearing bells over the websocket
- **loader:** code loaded on demand, its stylesheets first

For development: **repl**, on the server and in the browser.

Not Groundwork's: the login (it ends by calling sign-in-as), logs and metrics (through hooks like `:on-query`), team endpoints and an eval bridge ([Groundwork ships no team endpoints and no eval bridge](2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md)).

**Why:** these are the same in every app; what differs per app is a setting, a description or a hook. The list of 2026-10-12 named jobs as later, left the page shell and the store's name open, and let apps replace parts; all of that is decided or built now.

**The price:** every app carries every part. An app without background jobs still writes `:jobs nil`.

**Instead of:** keeping the list of 2026-10-12 with notes on top of notes.
