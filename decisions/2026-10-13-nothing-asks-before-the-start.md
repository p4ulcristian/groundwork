# Nothing asks before the start

> Adds to [The browser keeps answers by question](2026-10-12-the-browser-keeps-answers-by-question.md).

**Decision:** a question put to the store before the app's start is refused with an error that names the endpoint and says the app has not started. In development and in production alike.

**Why:** the start creates the store and sets how answers are read (which fields are times, which are decimals). A question asked while the code is still loading went out before either existed; its answer was dropped without an error and the screen waited forever. Such code asks on every page load, so a refusal shows the first time its author opens the page. Data a screen needs on arrival is declared on its route.

**Instead of:**
- forgetting the question silently (what happened before)
- the start keeping earlier answers: they were read before the types were set, so the screen gets wrong values instead of none
- holding early questions until the start: it works, and nobody learns the code asks at the wrong moment
