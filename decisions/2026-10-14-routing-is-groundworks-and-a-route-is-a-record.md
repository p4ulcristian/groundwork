> **Changed in part** by [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md) (2026-10-23): the routing extras (query, scrolling, the unsaved guard, modules on demand, languages) are in mailroom, parlor, showroom, dictionary and house.

# Routing is Groundwork's, and a route is a record

**Decision:** Groundwork owns routing in the browser. A route is a plain record: its address, who may open it, its title, its screen, the data it needs on arrival, and, only when the route says so, that its code loads on demand (with a placeholder) or that it holds unsaved changes. Languages in the address are a setting. An app goes through Groundwork to move, to make a link, and to read the address's values and filters; it never builds or reads addresses by hand. Records are shaped so the server and the browser can read the same one, so a page can later be declared once for both.

What every route gets: address to screen with values in the address, Back and Forward, the title on every move, scrolling (top on a new page, to an anchor, untouched when only filters change), its data asked on arrival and let go on leaving, filters in the address, an unknown-address screen, and a hook an app can listen to on every move. What a route or app declares to get: loading on demand, languages, the unsaved-changes guard.

An app's public addresses keep their shape: they are a promise to people's bookmarks and to search engines.

**Why:** an address leading to a screen, with its data, title and history, is what every web app needs and what every framework owns; left to each app, each builds it again. A record of what a route is survives a change of how routing works inside (a new browser feature, another way of drawing screens). One door means a change of opinion, such as how languages appear in the address, is made in one place.

**The price:** an app with its own router moves onto Groundwork's. The unsaved-changes guard works on the browser's history and is the delicate part.

**Instead of:** letting an app bring its own router (two routers live on, and it contradicts that apps don't replace parts); a thin router that leaves titles, scrolling, data and languages to each app.

**Added 2026-10-14:** unsaved changes hold every move away from the screen, and a filter that adds a step for Back. They don't hold the screen swapping its own query in place (`replace-query!`): nothing is left, and a screen that notes the open tab or the picked item in the address must be able to do it while its form is half filled.
