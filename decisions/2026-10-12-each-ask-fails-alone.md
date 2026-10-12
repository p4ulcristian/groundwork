# Each ask fails alone

**Decision:** an ask that fails doesn't take the others down; each gets its own answer or error.

- A first load that fails shows an error in its own area, with a way to try again. A refresh that fails keeps the old data on screen, with a small notice.
- The person sees: the input shape's own message for bad input; the login for not signed in; "not found" for not allowed or another scope's record; the app's own message for a business rule meant to be read; when to try again for a rate limit; "Something went wrong. Please try again." for anything else.
- Database messages, table and column names and stack traces never reach the browser. They go to the log with the request id, which the browser also gets, so a person can quote it.
- A failed write saved nothing (one transaction), so a form keeps what was typed; run-once makes the retry safe.

**Why:** a page stays usable when one part breaks, nothing internal leaks, and every failure can be found from one id.

**Instead of:** one error failing the whole page, or raw error text in the browser.
