# groundwork.browser.main

The browser's one start: reads what the server's page shell put in the page
(the version, the three addresses, the ws reopen delay, the pages), makes
the question store (groundwork.browser.sync), opens the sse line (sync's
messages, the app's own, and the version: a page older than the server
reloads, once per version) and follows the address bar. ClojureScript only. API:
`groundwork.browser.main.api` (`read-settings-from-page`, `start-app!`).

```clojure
(def app (main/start-app! {:shapes-by-name shapes-by-name :sse-handlers {} :on-page show-page!}))
(sync/watch-question! (:store app) [:orders/of-shop {:shop/id id}] show-orders!)
```

Not tested yet: no browser builds while the libraries are cut.
