# A page says its own head and body through the app's render-page; the composer keeps the shell and makes the nonce

> **Changed in part** by [A page's asker is found once, in its one transaction](2026-10-27-a-pages-asker-is-found-once-in-its-one-transaction.md) (2026-10-27): render-page runs in one read-only transaction as the asker and gets `{:nonce :asker :transaction}`, no `run-as-asker!`.

> **Changed in part** by [render-page answers a page or a redirect](2026-10-23-render-page-answers-a-page-or-a-redirect.md) (2026-10-23, built on tidy-up, not released): every answer writes `:answer`, `:page` or `:redirect`.

> **Changed in part** by [A token call arrives where a browser's does; the composer names no person](2026-10-23-a-token-call-arrives-where-a-browsers-does.md) (2026-10-23, built on tidy-up, not released): `run-as-asker!` runs as the asker doorman finds in the page's request (doorman's `run-as-asker-of-request!`).

> The user's decision, 2026-10-23, for Iron Rainbow's move to v1 (SEO titles and descriptions per page and language, the prerendered body crawlers read, `IR_CONFIG`, the boot splash, the nonce CSP, its 404). Changes in part the page-shell decisions (the shell had one title for every page and no body; `:page :title` is gone). Built the same day (`groundwork.server.main.page`, bridge v0.7.7's `:not-found`).

**Decision:** the app gives the composer one function, called for every page it answers:

```clojure
:render-page (fn [request page {:keys [nonce asker run-as-asker!]}]
               {:status      200                        ; or 404 for the not-found page
                :title       (tr :site.home/title {})
                :description (tr :site.home/description {})
                :head-html   (site-head request nonce)  ; canonical, Open Graph, the app's own scripts
                :body-html   (prerender request)})      ; inside #app, replaced when the app draws
:page {:content-security-policy {:nonce? true :policy "script-src 'nonce-{nonce}' 'strict-dynamic' …"}}
```

The composer still writes the shell: `<html lang>` in the request's language, the alternate links, its own scripts and the settings JSON (each with the request's nonce), the catalogs, the module stylesheets. It makes one nonce per request, puts it in the CSP header, and hands it to `render-page`. An address no page has is answered by `render-page` with the not-found page and its status.

**Why:** what a crawler and a person need is per page and per language (a title, a description, a body to read before the app draws); only the app knows it. One shell, written by the composer, keeps the scripts, the language and the CSP the same for every app.

**The price:** the app writes HTML strings for the head and body pieces and escapes what it puts in them; a page that reads data for its body needs the asker's transaction (the app's own routes decision's `run-as-asker!`).

**Instead of:**
- more static settings (an icon, head tags, a description per page record): nothing per request, so no SEO per language, no prerendered body, and no nonce
- the app answering its pages on its own routes: every app writes the shell again
