# Every page says who may frame it

> Decided by the agent releasing the composer, 2026-10-27, from the user's brief (the switch's decision 8 and S5: one framing setting covers every page today; a page says who may frame it, `frame-ancestors`, required on every page; Iron Rainbow's /customize: anyone, every other page only its own site). Since v8.0.0. Changes in part [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (the `:page :frame-options` setting is gone) and carries over [Pages for other sites, visitors, and servers](2026-10-12-pages-for-other-sites-visitors-and-servers.md)'s embeddable pages.

**Decision:** every page in the app map's `:pages` writes out `:frame-ancestors`, and the app map writes out `:not-found-frame-ancestors` for the not-found page:

| value | `X-Frame-Options` | the content security policy's |
|---|---|---|
| `:own-site` | `SAMEORIGIN` | `frame-ancestors 'self'` |
| `:no-site` | `DENY` | `frame-ancestors 'none'` |
| `:any-site` | none | none |

```clojure
:pages {:orders/page    {:address "/orders/:order-id" :title "Order" :module :main :frame-ancestors :own-site}
        :customize/page {:address "/customize" :title "Customize" :module :main :frame-ancestors :any-site}}   ; in shops' sites
:not-found-frame-ancestors :own-site
```

- The `:page :frame-options` setting is gone. The page's frame-ancestors are added to the `:content-security-policy` setting's policy (`…; frame-ancestors 'self'`), or sent as the policy alone when it is `:none`; a policy that names frame-ancestors itself is refused at the start.
- A page with none, or another value, refuses the start, `:groundwork.server.main/bad-pages`, every page named at once with the modules' problems.
- Both headers are sent: browsers that read the policy take `frame-ancestors`; the few that read only `X-Frame-Options` get it too.
- The browser's copy of the pages leaves `:frame-ancestors` out: only the server's headers say it.

**Why:** one setting framed every page the same: Iron Rainbow's customizer, which shops embed in their own sites, needs every site; its other pages, none but its own. A page's framing is the page's, as its module is.

**The price:** every page writes one more key, and the app map one more.

**Instead of:**
- **a list of the sites that may frame a page:** no page needs one yet; a fourth value can say it without changing the three
- **`:any-site` written as `frame-ancestors *`:** `*` leaves out frames of `data:` and `blob:` pages, so it is not "anyone"; saying nothing is
- **only the policy's `frame-ancestors`:** a browser that reads only `X-Frame-Options` would frame every page
