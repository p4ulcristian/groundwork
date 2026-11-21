# A page links a lost stylesheet again before its code runs

> Adds to [A page can load its scripts with progress](2026-11-19-a-page-can-load-its-scripts-with-progress.md). From Iron Rainbow, 2026-11-21: an iPad's first load of the customizer came up without its own stylesheet. The page and all four scripts arrived; three of the four stylesheets did; the biggest did not, somewhere between Cloudflare's cache and Safari. No browser retries a stylesheet by itself, and a hashed bundle a first visit lost stays lost until the next load, while the code starts anyway.

**Decision:** in `:fetched-with-progress`, the shell guards its own stylesheets:

- A small nonced inline script **before** the stylesheet links notes every stylesheet link whose `error` event fires (a capturing listener on `window`: the event doesn't bubble). The event is the one sign every browser gives; Chrome hands a failed link an empty sheet, so "no sheet" alone is not enough.
- The loader, which runs after the links (a script waits for the stylesheets before it), waits for the page to be parsed (an error event can still be on its way when it runs), then links each of the shell's stylesheets that was noted lost, or whose link has no sheet, **once more**, right after its old link. The script tags go in only once every second try has loaded or failed.
- The page hears how it went, either way: `groundwork:stylesheet-retry` on `window` with `{href, ok}`.

```js
window.addEventListener('groundwork:stylesheet-retry', e => e.detail);  // {href: '/css/app.1F2A….css', ok: true}
```

- **Only the shell's own stylesheets** (the page module's bundles, by the hrefs as written). The app's head links are the app's.
- **One retry.** A second failure is told and the code runs unstyled, as before: a screen without its styles beats no screen.
- **`:deferred-tags` retries nothing:** it writes no inline script, and a content security policy without a nonce may allow none.

**Why:** one lost fetch on a cold connection should cost one extra request, not a naked app until the next load; and the shell is the one place that knows which stylesheets the page needs before its code runs.

**Instead of:**
- **checking `link.sheet` alone:** Chrome gives a 404 link an empty sheet; only the error event says it failed
- **retrying in the app:** by the time the app runs, it is already on screen unstyled, and every app would do it again
- **turning off HTTP/3 at the CDN:** a guess at the cause, zone-wide, and no help against any other dropped fetch
