# The browser keeps answers by question

> **Changed in part** by [Asking again, and bells that miss nothing](2026-10-14-asking-again-and-bells-that-miss-nothing.md) (2026-10-14): the store can also ask a question again on request (a button, a timer, after a refused write), so "never on a timer" no longer holds.

**Decision:** the browser half keeps every answer under its question: the endpoint plus its params. Two asks of the same question make one request and one entry.

- A page or panel says what it needs when it opens; that fetches. Showing data never fetches by itself.
- A revisited screen shows its last answer at once while a fresh one loads, then replaces it.
- A question is asked again when a screen that needs it opens, or when live updates say what it shows changed; never on a timer.
- An answer no open screen needs is kept for 5 minutes, then dropped.
- When requests for one question overlap, only the newest answer counts.

**Why:** pages revisit instantly instead of blanking, nothing fetches by accident on a redraw, and a late answer can't overwrite fresher data.

**Instead of:** each screen fetching on its own, which repeats requests and shows spinners on every visit.

**Added 2026-10-14, for an app that moved its own store onto this one:**

- An ask may say how long its answer stays fresh (`:fresh-ms`): ensuring it again within that time asks nothing. Without it every ensure asks, as decided.
- Something can wait once for a question's answer (`once-answered`): a form filled from what the server last said. A failed answer tells it nothing.
- The app can hear of every read that failed (`:on-read-failed`), to say so its own way (a toast, the login).
- Asking again after a failed first load reads as loading, not as the old error.
- A write that brings its own key keeps it: a form holds one until it is saved.
- The browser start can mount into another element than `<div id="app">` (`:mount`), for a page that keeps a splash there.
- An owner can hear the bell for asks whose answers something else keeps (`hear`): a table with its own loader for paging and search. The better form is the table's page as a question of its own; this is for an app on its way there.
