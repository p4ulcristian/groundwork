# A page can load its scripts with progress

> The user's decision, 2026-11-19, from Iron Rainbow's boot splash: while the customizer's JavaScript downloads, the page cannot say how far it is, because a `<script defer>` tag's download tells the page nothing.

**Decision:** a required `:page` setting, `:script-loading`:

- `:deferred-tags`: a `<script defer>` for each of the page module's scripts, as before.
- `:fetched-with-progress`: one inline, nonced script in their place. It knows each file's size in the build (read per request), fetches them all at once, and fires `groundwork:script-progress` on `window` with `{loaded, total}` in bytes as the bytes arrive. Once all are in and the page is parsed, it adds their `<script>` tags in order (`async = false`), which the browser takes from its cache.

```js
window.addEventListener('groundwork:script-progress', e => {
  const {loaded, total} = e.detail;   // 412000, 1830000
});
```

- **One download:** the tags hit the cache the fetch filled. Nothing may preload the same files as scripts; a preload and a fetch do not share a download.
- **A failed fetch** is not counted and its tag still goes in: the page loads as a plain tag would.
- **The size is the file's own** (a fetch counts the body as decoded, whatever the compression).

**Why:** a real bar from real bytes, for the slow first visit that needs it most; the app draws it (the shell only tells).

**The price:** the scripts start after the inline script runs (at once, it is in the head) and run after the page is parsed, as deferred scripts do; DOMContentLoaded fires before they run, so app code must not wait for it.

**Instead of:**
- **the app fetching beside the shell's script tags:** every file downloaded twice on a first visit
- **counting finished files (Resource Timing):** a few jumps, the bar still while the big file downloads
