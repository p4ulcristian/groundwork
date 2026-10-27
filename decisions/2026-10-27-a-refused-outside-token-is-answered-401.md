# A refused outside token is answered 401; a person route names its challenge

> Decided by the agent releasing the composer, 2026-10-27, from the user's brief (handle `:groundwork-access/token-refused` where the composer runs the find-session step itself, page.clj, files.clj, errors.clj, and answer 401 for a bearer route). Since v8.0.0. Changes in part [An app's own server routes are records with a kind](2026-10-23-an-apps-own-server-routes-are-records-with-a-kind.md) (a `:person` route's keys) and [A token call arrives where a browser's does](2026-10-23-a-token-call-arrives-where-a-browsers-does.md) (what a refused one gets).

**Decision:** where the composer runs groundwork-access's find-session step on a request itself, a refusal is answered by one function, `groundwork.server.main.asker/answer-refused-request`:

- **`:groundwork-access/token-refused`** (an outside issuer's token that failed its check: expired, another audience, an unknown key, a subject no sign-in linked): **401**, `WWW-Authenticate: Bearer error="invalid_token"` (RFC 6750), so a client knows to get a new token;
- **any other reason** (`:groundwork-access/not-allowed`: another site's request, a cookie and a token at once): **403**, as before;
- the body is the reason's text in the request's language; never cached.

Where: a **page** (no `render-page` runs), an **upload** (before the body is read; a 403 stays groundwork-file-storage's own answer), an app's **`:person` route** (no handler runs). A **`:person` route** writes out `:bearer-challenge`, the auth-params its 401 carries beside `error="invalid_token"`, `{}` for none; an MCP server names its protected resource's metadata there, as MCP's authorization asks of a 401:

```clojure
:app/mcp {:address "/mcp" :method :post :kind :person :rate-limit nil
          :bearer-challenge {"resource_metadata" "https://shop.example/.well-known/oauth-protected-resource"}
          :handler mcp/answer}
;; 401  WWW-Authenticate: Bearer resource_metadata="https://shop.example/.well-known/oauth-protected-resource", error="invalid_token"
```

Each value is written in quotes as it is; one holding a quote, a backslash or a control character is refused at the start, and so is a `:bearer-challenge` on a `:provider` route, which reads no token. A **message** refused so (`:groundwork/browser-error`, every handler) is answered as every refusal, inside the 200, `:groundwork-access/token-refused`; `:groundwork/browser-error` declares it.

**Why:** a 403 tells a client its token is fine and the answer is no; a token that failed its check needs a new one, which is what 401 with `invalid_token` says. A route serving MCP must say where its authorization server is in that 401, which only the app knows.

**The price:** every `:person` route writes one more key. A page asked with a refused token gets text, not the shell.

**Instead of:**
- **the route's own handler called with the refusal:** every `:person` handler would branch on it; the composer's answer is one
- **`:bearer-challenge` a setting:** it belongs to a route; an app with two token routes may name two resources
- **escaping a quote in a value:** no value an app writes there needs one; refusing keeps the header exactly what was written
