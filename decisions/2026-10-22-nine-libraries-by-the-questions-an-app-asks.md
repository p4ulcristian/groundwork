# Nine libraries, by the questions an app asks

> **Changed in part** by [A library says where it runs](2026-10-22-a-library-says-where-it-runs.md) (2026-10-22): transport is transport-server and transport-browser; auth is sessions and access again.

> Changes in part [A library holds one idea and knows nothing of who uses it](2026-10-22-a-library-holds-one-idea-and-knows-nothing-of-who-uses-it.md), [web becomes transport and routes](2026-10-22-transport-and-routes.md) and [The first cut is thirteen libraries](2026-10-22-the-first-cut-is-thirteen-libraries.md): three pairs become one library each.

**Decision:** Groundwork's libraries answer the questions an app asks, one each:

| question | library |
|---|---|
| how does data keep its types? | wire |
| how is work written? | steps |
| how do I talk to Postgres, and shape its tables? | **database** (was sql + schema) |
| how do browser and server talk, at which addresses? | **transport** (was transport + routes, with rate limits) |
| who is asking, and what may they do? | **auth** (was sessions + access) |
| what runs later? | background-chores |
| how do screens stay current? | sync |
| what does the browser download? | browser-build (with styles) |
| how does it all start? | main |

Inside a merged library each half keeps its own files (`server_code/queries/…` and `server_code/tables/…`; `server_code/sessions/…` and `server_code/access/…`), so its folder still shows both. Its keywords carry the merged library's name (`:groundwork.auth/person-id`); its Postgres tables keep their names.

**Why:** twelve folders read as chaos; nine questions read as the framework. Each merged pair already depended on its other half and changed with it.

**The price:** a merged library is bigger, and its halves can't be taken alone (sessions without access, sql without schema).

**Instead of:** twelve libraries grouped only in the README: nothing moves, but the folder list stays twelve.
