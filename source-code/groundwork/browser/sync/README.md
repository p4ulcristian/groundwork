# groundwork.browser.sync

Keeping what screens show in step with the database, the browser's side: a
store keeping answers by question (`[name params]`), each with the tables
the server says it was read from; screens watch questions; a
`{:sync/changed {:table …}}` from the server (only the table: the gates
decide what each answer holds) asks again the watched questions that read
that table (gathered for 50 ms); `{:sync/missed {}}`,
or the sse line opening again, asks again all of them. Overlapping asks:
the newest answer counts; an unwatched answer is kept 5 minutes. The
server's side is [groundwork.server.sync](../../server/sync/README.md).

ClojureScript only. Needs nothing but what it is handed (a send-message!
function); groundwork.browser.main makes the store. API:
`groundwork.browser.sync.api` (`make-question-store`, `watch-question!`,
`ask-question-again!`, `find-kept-answer`, `make-sse-handlers`,
`hear-missed-changes!`).

Not tested yet: no browser builds while the libraries are cut.
