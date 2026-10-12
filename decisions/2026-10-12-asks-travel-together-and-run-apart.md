# Asks travel together and run apart

**Decision:** the asks a page makes at the same moment travel in one request. On the server each runs on its own, in parallel, with its own transaction and its own answer; one failing doesn't affect the others. A request carries at most a set number of asks, and across the server only a set number run at the same moment, well below the database pool's size, so a burst of pages can't take every connection.

**Why:** a page waits only for its slowest ask, and one round trip carries them all. Asks running apart can see slightly different moments (a count one off from a list for an instant); live updates refresh both right after the write that caused it.

**Instead of:** all asks of a request in one transaction. Always consistent, but one after another on one connection, so every page is slower, and one failure fails them all.
