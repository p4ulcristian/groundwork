# main makes one wire and passes it to every library

> Changes in part [main takes every library's new settings](2026-10-22-main-takes-every-librarys-new-settings.md) and [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md): two settings parts more, the page carries the browser's wire settings, and house takes wire v0.2.0, mailroom v0.2.0, backroom v0.2.0, doorman v0.2.0 and doorbell v0.2.0.

**Decision:** wire v0.2.0 makes a wire from settings, and every one of its functions takes the wire first. house makes the app's one wire on each side and passes the same value to every library that sends or reads data across the app's edges, as a required argument or option; no library makes its own wire or carries wire settings ("the app passes its wire; the library never makes one", each library's decision).

- **`:wire`**, required in house's settings: wire's settings on the JVM, `wire.api/settings-shape`, every key written out. `start-app!` checks the settings, then makes the wire once, `(wire/make-wire-from-settings (:wire settings))`, and passes it on: mailroom's `make-routes-for-messages` options `:wire` (every message; each sse and ws line carries it, so doorbell's listener and the version message send by it), backroom's `start-workers!` options `:wire` (every background job's params), the page shell's JSON and the health checks' answers. The running app holds it, `:wire`.

  ```clojure
  :wire {:most-shapes-kept 1000
         :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                            :max-name-length 50000 :max-document-length nil :max-token-count nil
                            :max-writing-nesting-depth 1000}}
  ```

- **`:browser-wire`**, required: the browser's wire settings but `:exact-decimals`, `{:most-shapes-kept 1000 :most-keys-kept 10000}`. The page carries them, under `"wire"`.
- **`:exact-decimals`**, a required option of `house.browser.main`'s `start-app!`: `wire.big/big` (big.js, in the app's `package.json`) or nil, a decimal kept as its text. It is a function, so the page can't carry it; the app's browser start writes it. `start-app!` makes the browser's wire from the page's settings and it, passes it to mailroom's `send-message!` (the question store's asks) and `open-sse-line!`, and returns it, `:wire`, for the app's own lines. Its options are checked by malli, every key required (`:house.browser.main/bad-options`).

  ```clojure
  (main/start-app! {:shapes-by-name shapes-by-name :sse-handlers {} :on-page show-page!
                    :exact-decimals wire.big/big})
  ```

- The browser reads the page's settings with `JSON.parse`, keys as keywords with their namespace, as wire wrote them: the wire is made from what it reads, so none exists yet.
- The settings, house's and every library's, are checked by malli itself, not through wire's `find-errors-in-data-for-shape`: they are values the app hands in, and the wire is made from them only once they fit.

**Why:** a wire is settings (Jackson's limits, how many shapes are kept, whether decimals are big.js) and what it keeps (each shape's tools). One wire made by house from the app's settings is one value in effect at every edge, shown in the app's settings: the 20-million-character string limit, the thousand shapes kept. A library making its own would hide its settings again and keep its tools twice. Until wire v0.2.0 these values were wire's, unwritten, and loading `wire.big` anywhere switched big.js on everywhere; house's browser start never loaded it, so decimals stayed text, a choice no file said.

**The price:** an app's settings grow by two parts, `:wire` (2 keys, 7 more in `:json-limits`) and `:browser-wire` (2 keys); its browser start writes `:exact-decimals`. App code that queues a background job (backroom's `add-background-job-to-queue!`) needs the wire at hand, which `start-app!` returns after the handlers are made.

**Instead of:**
- each library making its own wire from settings of its own: several wires in one app, settings in several places, the browser's decimals possibly held two ways
- one `:wire` part with `:server` and `:browser` inside, as `:doorbell` holds `:store` and `:listening`: the JVM's settings are wire's own shape as it is, and the browser's lack `:exact-decimals`, so neither part is wire's browser shape anyway
- `:exact-decimals` as a setting naming a function (`:big` or `:text`): a mapping house would keep beside wire's, and wire takes the function itself
- reading the page's settings through a wire made with fixed settings first: a hidden default, the thing wire v0.2.0 removed
