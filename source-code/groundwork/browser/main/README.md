# groundwork.browser.main

The browser's one start: reads what the server's page shell put in the page
(the version, the three addresses, the sse and ws reopen delays, the
browser's groundwork-shaped-json settings, the pages), makes the app's one shaper in the browser
from those and its required `:exact-decimals` option (`groundwork-shaped-json.big/big` or nil),
passes it to bridge and returns it (`:shaped-json`), makes the question store (groundwork-live-updates's, with the page's `:live-updates`
settings and the page's document, `{:page js/document}`: a page nobody sees asks again once, when seen), opens the sse line (groundwork-live-updates's messages, the app's own, and the version: a page older than
the server reloads, once per version; reopened after the sse delay when the
server answered with an error; the app's `:on-line-change` dispatched with
`{:line :open}` when it first opens, `{:line :broken}` once per break and
`{:line :back}` when it opens again, an offline banner's status), then starts groundwork-screens (`[:groundwork-screens/start …]`, with
the store, the write's send function (the send's options as `:groundwork-screens/send`
wrote them, `{:once-key :keep-alive?}`: the key in the `idempotency-key`
header, `:keep-alive?` as bridge's; each table a good answer's notes name
told the store as this browser's own change, groundwork-live-updates's
`hear-own-changed-table!`, so what read it is asked again at once, past
`:ask-again-at-most-every-ms`), the page's `:retry-writes`, the pages, the app's `:questions-of-pages`,
`:on-answer-failed`, `:on-signed-out` (with `:groundwork-access/signed-out` as groundwork-screens's
`:signed-out-reason`, both nil when the app's is), `:on-move`, `:query-shapes-of-pages` and `:placeholder`, the page's
languages and language, browser-build's `load-module!` with each module's stylesheets, and `[:groundwork-access/my-actions {}]` as its
`:actions-question`), which follows the address bar. The app draws its root
with groundwork-screens's `mount-root!` into the shell's `#app`. Once for the
page, it sends every uncaught error and unhandled rejection to the server,
`{:groundwork/browser-error {:kind :uncaught :version … :message … :stack … :url …}}`,
where it is logged at `:error` with the server's errors, the one list the
app's monitor reads; `report-browser-error!` sends one an app's error
boundary caught, `(main/report-browser-error! app {:kind :render :error e})`,
`app` what `start-app!` returned. `:version` is the page's version. The app's
shapes have the three for each of its names, `:params-shape`,
`:answer-shape` and `:refusals` (its handler's own reasons, `{}` for
none); groundwork-access's reasons (groundwork-access's `put-refusals-into-shapes`, `:groundwork-access/token-refused` among them) and
groundwork's run-once ones are added to each, and groundwork's own names
(`:groundwork-access/my-actions`'s answer shape is groundwork-access's, which groundwork-screens's "can I?"
checks the answer by, with the app's shaper). A view shows a refusal by
its reason, `(screens/tr (:groundwork-steps/refused error) (:groundwork-steps/data error))`. The app's `package.json` lists
big.js, react, react-dom and process as `dependencies`: browser-build's release
stops on one installed only for development. ClojureScript only. API:
`groundwork.browser.main.api` (`read-settings-from-page`, `start-app!`,
`report-browser-error!`,
`start-visitor-session!`: a session for a visitor, the first time the app
keeps something for them, a POST to the page's `:visitor-path`; a promise
of the visitor's id).

```clojure
(def app (main/start-app! {:shapes-by-name     shapes-by-name
                           :sse-handlers       {}
                           :questions-of-pages {:orders/page (fn [{:keys [path-params]}]
                                                               [[:orders/of-shop {:shop/id (parse-uuid (:shop-id path-params))}]])}
                           :on-answer-failed   nil              ; or an event
                           :on-signed-out      [:app/to-login]  ; or nil: once, with the page to return to
                           :on-move            nil              ; or an event
                           :on-line-change     nil              ; or an event, given {:line :open}, :broken or :back
                           :query-shapes-of-pages {:orders/page nil}   ; every page's: a malli map reads its query, nil text
                           :placeholder        nil              ; or (fn [page status]) while a page's :module loads
                           :exact-decimals     groundwork-shaped-json.big/big}))  ; or nil: a decimal stays its text
(screens/mount-root! "app" root)                                 ; again after a save; root draws [screens/show-page draw-page]
```

Tested by the release build check (`test-code/groundwork/browser/build_check_test.clj`):
an entry using every browser library compiles.
