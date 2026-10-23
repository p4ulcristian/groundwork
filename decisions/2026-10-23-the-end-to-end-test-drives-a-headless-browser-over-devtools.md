# The end-to-end test drives a sample app in a headless browser, over the DevTools protocol

> The user's decision, 2026-10-23.

**Decision:** `house.end-to-end-test` starts a sample app (`test-code/house_test/sample/`: shops and orders, Anna and Bob in different shops, one page "orders of my shop" with "add order", English and German, one module loaded on demand) with house's own `start-app!` on a throwaway Postgres, its browser half release-built by showroom, and drives it in a headless browser through the Chrome DevTools protocol, from the JVM: `house-test.cdp`, the JVM's `HttpClient` websocket and jsonista, no npm package. The browser is whatever headless binary `HOUSE_E2E_BROWSER` names (Playwright's headless shell, or Chrome, which is given `--headless`); no window opens, and the user's browser is never touched. It runs only when `HOUSE_SQL_TEST_PORT` and `HOUSE_E2E_BROWSER` are both set.

It proves: the page and its language, German from the catalog; the sse line; the gates; doorbell's ring re-asking on another open screen without a reload, and not changing a screen of another shop; `can-i?`; a write whose answer is lost sent again with the same one-time key (the answer failed by the protocol's Fetch domain) saving once and answering the same; a browser error at the errors route; the metrics; ready 200 and 503 while stopping; a module's stylesheet in before its code; the unsaved guard; a dropped sse line closed by the next ping.

**Why:** the test runs in the same JVM as the app, so it can start and stop it, read the database and the metrics, and assert in `clojure.test` beside every other test. The protocol gives what a browser automation library would (pages, cookies per context, dialogs, network events, failing a response) in about 150 lines.

**The price:** `house-test.cdp` is a small client of house's own to keep; a protocol change in a new browser breaks it before a library would notice. The release build makes the run take about half a minute.

**Instead of:**
- Playwright from npm: a second language and process for the test, and the app started apart from it
- a JVM WebDriver library: a big dependency, and a driver binary matched to the browser
- the real Chrome profile: never; it is the user's
