# house.browser.main

The browser's one start: reads what the server's page shell put in the page
(the version, the three addresses, the sse and ws reopen delays, the
browser's wire settings, the pages), makes the app's one wire in the browser
from those and its required `:exact-decimals` option (`wire.big/big` or nil),
passes it to mailroom and returns it (`:wire`), makes the question store (doorbell's, with the page's `:doorbell`
settings), opens the sse line (doorbell's messages, the app's own, and the version: a page older than
the server reloads, once per version; reopened after the sse delay when the
server answered with an error) and follows the address bar. Once for the
page, it sends every uncaught error and unhandled rejection to the server,
`{:house/browser-error {:message … :stack … :url …}}`, where it is logged at
`:error` with the server's errors. The app's
shapes have both shapes for each of its names; house's own are added. ClojureScript only. API:
`house.browser.main.api` (`read-settings-from-page`, `start-app!`).

```clojure
(def app (main/start-app! {:shapes-by-name shapes-by-name :sse-handlers {} :on-page show-page!
                           :exact-decimals wire.big/big}))   ; or nil: a decimal stays its text
(doorbell/watch-question! (:store app) [:orders/of-shop {:shop/id id}] show-orders!)
```

Not tested yet: no browser builds while the libraries are cut.
