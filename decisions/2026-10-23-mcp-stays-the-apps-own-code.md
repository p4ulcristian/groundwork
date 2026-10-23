# /mcp stays the app's own code, not a groundwork library

> The user's decision, 2026-10-23.

**Decision:** an app's MCP endpoint (`/mcp`, which AI tools call: Iron Rainbow's, behind its own login) is the app's own code, written in the app on groundwork's parts. groundwork ships no MCP library and no MCP route.

**Why:** what an MCP endpoint offers (its tools, their names, what each may do and for whom, how its callers sign in) is the app's own business; Iron Rainbow is the only app with one. A library would hold one idea for one user, and would have to guess the next app's tools.

**Instead of:**
- an MCP library in the family: built for one app, its shape guessed for the others
- an MCP route in the composer: every app would carry an endpoint few want
