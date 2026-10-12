# Groundwork ships the page shell

**Decision:** Groundwork serves the HTML page that loads an app: the document, its compiled ClojureScript and styles, and what the browser half needs to start. The app gives its own details (title, icon, its stylesheet, extra head tags) as settings.

**Why:** the page is the same in every app apart from those details, and getting it right (caching of compiled files, security headers, what a signed-out visitor sees) is the kind of thing to solve once.

**Instead of:** each app writing its own page, which drifts and repeats the same fixes.
