# The question store v1

> **Changed in part** by [sync tells by table and trusts the gates](../../2026-10-22-sync-tells-by-table-and-trusts-the-gates.md) (2026-10-22): `{:sync/changed …}` carries no `:ids` any more, so a screen of one record is asked again on any change to its table by design, not for now.

> Builds on [The browser keeps answers by question](../../2026-10-12-the-browser-keeps-answers-by-question.md), [Asking again, and bells that miss nothing](../../2026-10-14-asking-again-and-bells-that-miss-nothing.md) and [Nothing asks before the start](../../2026-10-13-nothing-asks-before-the-start.md).

**Decision:** groundwork.browser.sync keeps every answer under its question, `[name params]`, with the tables the server's notes say it was read from.

- A screen watches a question (`watch-question!`): it gets the kept answer at once, the question is asked, and every new answer reaches it. Stopping returns from the call.
- Two asks while one is out make one request; the question is asked once more when it lands. When asks overlap, only the newest answer counts.
- `{:sync/changed {:table …}}` asks again every watched question that read the table, gathered for 50 ms; `"any"` tables means every change. `{:sync/missed {}}`, or the sse line opening again after a break, asks again every watched question.
- An answer no screen watches is kept 5 minutes, then dropped. `ask-question-again!` is for a refresh button or a try-again.
- The store is made by groundwork.browser.main's start, so nothing can ask before it.

**Why:** screens show their last answer at once, nothing asks twice, a late answer can't overwrite a fresher one, and only questions a change touches are asked again.

**The price, and what v1 leaves out:** `:ids` aren't used yet (a screen of one record is asked again on any change to its table); no `:fresh-ms`, `once-answered` or `:on-read-failed` (2026-10-12 additions) yet; no rendering: a watcher is a plain function, and the UI library is the app's.

**Instead of:** each screen asking on its own (repeated requests, spinners on every visit).
