# No pre-rendering of pages for search engines, for now

> The user's decision, 2026-10-23.

**Decision:** groundwork doesn't render pages on the server for search engines (no server-side rendering of the app's screens, no snapshots served to crawlers) for now. A page is the shell (its `<title>`, `<html lang>`, the alternate links for each language) and the browser draws the rest, as before.

**Why:** the apps built on groundwork so far are tools people sign in to; the pages a search engine should find are few, and the shell already says their language and their addresses in every language. Rendering on the server would need parlor's screens to run on the JVM, or a headless browser beside the app: a second way to draw every page, to keep in step.

**The price:** a crawler that runs no JavaScript sees the shell only. A public page that must be indexed with its content waits for this to be decided again.

**Instead of:**
- server-side rendering of parlor's screens: every screen drawn two ways
- pre-rendered snapshots for crawlers: a headless browser in production, and pages that can go stale
