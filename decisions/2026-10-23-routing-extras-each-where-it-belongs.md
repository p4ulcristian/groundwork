# The routing extras, each where it belongs: queries, scrolling, the unsaved guard, modules on demand, languages

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): dictionary is groundwork-languages now (`groundwork-languages.api`).

> The user's decision, 2026-10-23: as Groundwork v0.5.0 had them (`src/groundwork/browser/routing.cljs`, `browser/loader.cljs`, `browser.cljs`, `web/languages.clj`), and the place of each piece decided in building. Takes dictionary v0.1.0 (new), mailroom v0.6.0, showroom v0.3.0, parlor v0.5.0 and doorbell v0.4.1 (on mailroom v0.6.0). Carries over [A build can have several modules](2026-10-12-a-build-can-have-several-modules.md) and [Routing is Groundwork's, and a route is a record](2026-10-14-routing-is-groundworks-and-a-route-is-a-record.md). Changes in part [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (`:page :language` is gone, `:languages` is new) and [The browser start starts parlor](2026-10-23-the-browser-start-starts-parlor.md) (two more options).

**Decision:** each piece is in the library whose idea it is; house puts them together.

| piece | where | why there |
|---|---|---|
| the language of a request, a page, an address; the alternate links | **dictionary**, a new library (both sides) | one set of rules read by the server, the routing and the shell |
| parsing an address (path params, query, `#fragment`, language prefix through dictionary's functions), making one, the history, where Back left the scroll, asking before leaving | **mailroom**'s browser routing | the address bar is its: it knows which move it was |
| the page as re-frame data, `:parlor/go-to` with a query, scrolling once drawn, `[:parlor/hold message]` / `[:parlor/let-go]`, loading a page's module on the way in, `show-page` with a placeholder | **parlor** | it knows the db, when the page is drawn, what the person holds |
| several modules, each with its stylesheet bundle; `load-module!`, stylesheets before code | **showroom** | the build's, and its loader runs the build's files |
| the request's language, `<html lang>` and alternate links in the shell; the prefix taken off before routing; what the page carries | **house** (`house.server.main.page`) | the shell and the start are house's |

- **house's settings**: `:languages`, dictionary's settings, every key required; `:page :language` is gone, the shell's `lang` is the request's. The alternate links use the app's origin, the `:sessions` settings' `:allowed-origin` (the origin the app's pages are served from, which doorman checks), so no second setting says it again.

  ```clojure
  :languages {:languages ["en" "de"] :default "en" :cookie-name "lang"}   ; or nil: no cookie read
  ```

- **Every request** first gets its language (dictionary's `find-language-of-request`: the prefix, the cookie, Accept-Language, the default), and its `:uri` loses the prefix, so `/de/orders/7` is routed as `/orders/7` (`page/wrap-languages`, around mailroom's Ring handler).
- **The shell**, now written per request: `<html lang>` is the request's language; with more than one language, `<link rel="alternate" hreflang>` for each and `x-default`; its JSON carries `:language`, `:languages` (dictionary's settings), `:module-stylesheets` (each module's bundle, by name: `/css/<module>.css` while watching, the manifest's hashed ones when built) and, in `:pages`, each page's `:module`. It lists `main`'s script and stylesheet only.
- **The app** gains `:browser-modules`, showroom's `:modules` (`{}` for none), which the watcher gets and which the release alias's `:exec-args` writes as `:modules`; a page may name one, `:module "admin"`, and a page naming another refuses the start (`:house.server.main/bad-pages`).
- **The browser start** takes two more options, every key required: `:query-shapes-of-pages` (every page's, nil for text) and `:placeholder` (`(fn [page status])`, nil when no page names a module); it gives parlor dictionary's settings and the page's language, and showroom's `load-module!` with the page's `:module-stylesheets`.

**Why:** v0.5.0 had all of it in one browser namespace and the shell; split now, each part follows its library's idea and can be read alone, and the libraries stay ignorant of each other (mailroom takes dictionary's functions, not dictionary). The prefix is taken off at the edge, so mailroom's pages, the files and the three addresses need no language at all.

**The price:** an app writes `:languages`, `:browser-modules`, a query shape for every page and a placeholder; the shell is written for each request instead of once (a short string, the files read once). One language still writes `:languages`, `{:languages ["en"] :default "en" :cookie-name nil}`.

**Instead of:**
- the routing pieces in one library (v0.5.0's `groundwork.browser.routing`): the address, the db, the build and the languages in one file, none of them separable
- a `:page :origin` setting for the alternate links: the same value as `:allowed-origin`, said twice
- the language in each page's address (`/:lang/orders`): every page written twice, and the default's address would change
