# A page's asker is found once, in its one transaction

> Decided by the agent releasing the composer, 2026-10-27, from the user's brief (page.clj runs the find-session step and then `run-as-asker-of-request!`, which looks the session up a second time: make it once), on groundwork-access v0.8.3's [run-as-asker-of-request! hands f the asker it found](https://github.com/p4ulcristian/groundwork-access/blob/main/decisions/2026-10-27-run-as-asker-of-request-hands-f-the-asker-it-found.md). Since v8.0.0. Changes in part [A page says its own head and body through the app's render-page](2026-10-23-a-page-says-its-own-head-and-body-through-the-apps-render-page.md): render-page's third argument.

**Decision:** the page shell runs the app's `render-page` inside one read-only transaction as the request's asker, groundwork-access's `run-as-asker-of-request!`, which looks the asker up once, as the transaction's first statement, and hands it over:

```clojure
:render-page (fn [request page {:keys [nonce asker transaction]}]   ; was {:keys [nonce asker run-as-asker!]}
               (if (nil? (:person-id asker))
                 {:answer :redirect :status 303 :location "/login"}
                 {:answer :page … :body-html (draw-orders (postgres/find-rows transaction list-orders {}))}))
```

- `asker` is `{:person-id :visitor-id :session-flags :token-id :outside-issuer}`; `transaction` is held by the gates as that asker, read-only (a page is a GET: it reads).
- A request groundwork-access refuses runs no `render-page` and gets no shell ([A refused outside token is answered 401](2026-10-27-a-refused-outside-token-is-answered-401.md)); until now a refused page was drawn for nobody.
- A refusal `render-page` throws itself is the app's and is thrown on, not answered as the request's.

**Why:** a page read its asker on the pool (one checkout, one lookup) and, when its body read rows, looked the same session up again in a second transaction: two checkouts and two lookups for one page. Now one of each; the test counts `groundwork_access/find_session.sql` once per page.

**The price:** every page holds one pool connection while `render-page` runs, also a page that reads no rows; a `render-page` that writes is refused by Postgres (read-only).

**Instead of:**
- **`:run-as-asker!` kept, the asker taken from the step:** the second lookup stays
- **a read-write transaction for pages:** a GET that writes is a bug the read-only transaction catches
