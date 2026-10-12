# The browser keeps answers by question

**Decision:** the browser half keeps every answer under its question: the endpoint plus its params. Two asks of the same question make one request and one entry.

- A page or panel says what it needs when it opens; that fetches. Showing data never fetches by itself.
- A revisited screen shows its last answer at once while a fresh one loads, then replaces it.
- A question is asked again when a screen that needs it opens, or when live updates say what it shows changed; never on a timer.
- An answer no open screen needs is kept for 5 minutes, then dropped.
- When requests for one question overlap, only the newest answer counts.

**Why:** pages revisit instantly instead of blanking, nothing fetches by accident on a redraw, and a late answer can't overwrite fresher data.

**Instead of:** each screen fetching on its own, which repeats requests and shows spinners on every visit.
