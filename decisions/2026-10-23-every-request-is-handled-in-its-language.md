# Every request is handled with its language bound, for dictionary's tr

> The user's decision, 2026-10-23: "we want (tr ...)" (dictionary's [Texts are `(tr key args)`, in the language bound around them](https://github.com/p4ulcristian/dictionary/blob/main/decisions/2026-10-23-texts-are-tr-under-a-bound-language.md)). Adds to [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md).

**Decision:** the request's language, which groundwork already finds for every request (dictionary's `find-language-of-request`, around the routes), is bound with the app's texts (dictionary's `with-language`) while the request is handled. A handler, a page or a gate writes `(dictionary/tr :orders/count {:count 2})` and gets the request's language. Outside a request (an email, a PDF, a background job) the app binds the language of the row with the running app's `:texts`.

**Why here:** groundwork is where every request passes, and where the language is found; binding it there is one place, as Iron Rainbow's `wrap-locale` binds `*locale*`.

**The price:** a message call from the browser (bridge's http, sse, ws addresses carry no language prefix) is in the cookie's language, else the browser's Accept-Language, else the default, not the page's: the same as Iron Rainbow's. Work a handler hands to a thread Clojure didn't start has nothing bound; `tr` there throws, naming the key.

**Instead of:**
- each handler binding its own: the same lines in every handler
- the language as a handler's argument, handed to `make-text`: the call the user didn't want
