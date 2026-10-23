# render-page answers a page or a redirect

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, G1: 54 GET pages send a signed-out person to the login). Changes in part [A page says its own head and body through the app's render-page](2026-10-23-a-page-says-its-own-head-and-body-through-the-apps-render-page.md). Not built yet.

**Decision:** the app's `render-page` answers one of two maps, every key written out:

```clojure
{:answer :page     :status 200 :title "Customers" :description nil :head-html "" :body-html ""}
{:answer :redirect :status 303 :location "/login?next=%2Fflex%2Fcustomers"}
```

For `:redirect` the composer answers the status and `Location` and draws no page shell. The status is one of 301, 302, 303, 307, 308. In the browser, a page reached by parlor after the first one is guarded by parlor's `:on-signed-out` as before; this is the first, server-drawn page.

**Why:** one function decides for each request: signed out, not a member of this shop, a page moved. No 200 that is really a login page.

**The price:** every page answer writes `:answer`.

**Instead of:**
- **a page record saying `:when-signed-out {:redirect-to …}`**: data, but it covers only "signed out"; a shop the person isn't in still needs code
- **drawing the page and redirecting in the browser**: a 200 that is a login page, indexed by crawlers, and a flash
