# The composer takes named refusals: handlers write :refusals, every reason has its text, the browser draws it with tr

> **Changed in part** by [The composer plugs together the libraries that share groundwork-steps](2026-10-24-the-composer-plugs-the-libraries-that-share-groundwork-steps.md) (2026-10-24): a refusal's keys and the reasons every answer may carry are groundwork-steps's, `:groundwork-steps/refused`, `:groundwork-steps/data`, `:groundwork-steps/too-many` and the rest; every catalog holds their texts.

> The composer's part of bridge's [A refusal is a named reason, declared on its handler](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md) (the user's decision, 2026-10-23, with its six sub-decisions), of bridge's [Every answered message is counted on the meter, whichever door](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-every-answered-message-is-counted-on-the-meter-whichever-door.md), of dictionary's [tr is the one text call on the server](https://github.com/p4ulcristian/dictionary/blob/tidy-up/decisions/2026-10-23-tr-is-the-one-text-call-on-the-server.md) and of parlor's [tr is called only in views](https://github.com/p4ulcristian/parlor/blob/tidy-up/decisions/2026-10-23-tr-is-called-only-in-views.md). Changes in part [Every write runs once, by its one-time key](2026-10-23-every-write-runs-once-by-its-one-time-key.md), [house reads the catalogs at the start](2026-10-23-house-reads-the-catalogs-at-the-start-and-serves-them.md) and [The browser start starts parlor](2026-10-23-the-browser-start-starts-parlor.md). Built on tidy-up, not released.

**Decision:** what those decisions left for the composer.

- **Every handler writes `:refusals`** (`{}` for none), on both sides (the sample's shared `.cljc` shapes); doorman's three are added to every handler it wraps, and to every name in the browser (doorman's `put-refusals-into-shapes`).
- **The run-once texts are reasons:** `:groundwork/once-key-not-a-uuid` and `:groundwork/once-key-of-someone-else`, nil data, groundwork's own, added to every write's `:refusals`.
- **The start checks every reason's text** (dictionary's `check-texts-of-reasons`): bridge's five, doorman's three, groundwork's two and every ready handler's, in every catalog that shows; one missing refuses the start, `:dictionary/missing-texts`.
- **The browser start passes `:on-signed-out`** (the app's, a new required option, nil for none) to parlor, with `:doorman/signed-out` as its `:signed-out-reason` (nil when the app's is nil).
- **Texts are `tr` only:** the server's tests and README use `dictionary/tr` under `with-language`, never `make-text`; the sample's and the build check's views use `parlor/tr`, and show a refusal as `(parlor/tr (:bridge/refused error) (:bridge/data error))`.
- **`answer-as` and the REPL's `ask-as-person` count on the app's meter,** in the `:server` settings' `:metric-buckets`.

```clojure
;; a handler's shapes, both sides
{:orders/add {:params-shape [:map [:order/shop-id :uuid] [:order/note [:string {:min 1}]]]
              :answer-shape [:map [:order/id :uuid]]
              :refusals     {}}}

;; every reason's text, in every language (en.json)
{"doorman/not-found": "Not found.", "bridge/too-many": "Too many requests: try again in {retry_after_ms} ms.", …}
```

**Why:** the reasons are said once, where they are made, and the browser tells them apart by name; a reason without a text is found at the start, not by a person.

## Settled while building

Choices made while building, not asked:

1. **groundwork's reasons are added to writes only** on the server (the run-once step wraps only them), and to every name in the browser, which can't tell a write from a read; a read is never refused with them.
2. **A handler that declares one of groundwork's reasons is refused** at the start, `:groundwork.server.main/bad-handlers`, as doorman refuses one declaring its own.
3. **A kept refusal answers a repeat as itself:** a keyed write that was refused keeps the reason as text and its data as JSON by the reason's shape; a repeat gets the same refusal back, the reason a keyword and the data shaped again.
4. **`:groundwork/browser-error` declares `:doorman/not-allowed`** itself (its find-session step's reason; it isn't put through doorman's access), on both sides.
5. **A `:person` route's 403** (doorman's step refusing a cookie and a token at once, or another site) has the text of its reason, `:doorman/not-allowed`, in the request's language as its body, not English fixed in the composer.
6. **The texts are checked once the handlers are ready,** after the pool starts (doorman's wrapping needs it, and puts its reasons in); a failed check stops what had started.
7. **The REPL makes the server's names:** its ready handlers include `:groundwork/browser-error`, so bridge declares its metrics on the app's meter with the same table of names as the routes (meter refuses a second, different table).
8. **The browser start requires each app shape's three keys** (`:params-shape`, `:answer-shape`, `:refusals`), so a missing `:refusals` isn't filled in by the reasons groundwork adds.
9. **The test app and the sample** have a text for every reason in English and German; the sample's other shop answers `:doorman/not-found`, whose English text is "Not found.", which the end-to-end test reads.
10. **The sample's unsaved guard holds a key,** `[:parlor/hold {:text :orders/unsaved :args {}}]`, by parlor's added part of its tr decision.
11. **The test app's memberships hold roles by id** (doorman's roles decision): `add-scope!`'s ids by name, `add-membership!` with a set of them, `list-roles` to find another shop's.

**Instead of:** the composer writing English for its own refusals ("The one-time key must be a UUID."): the only texts on the wire no catalog says.
