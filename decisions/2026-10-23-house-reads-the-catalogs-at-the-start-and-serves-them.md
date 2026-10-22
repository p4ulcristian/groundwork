# house reads the catalogs at the start, serves them to the browser, and holds the server's texts

> The user's decision, 2026-10-23 (dictionary's texts, carried over from Iron Rainbow's i18n). Takes dictionary v0.2.0 (texts: ICU messages in a JSON catalog per language) and parlor v0.7.0 (texts are re-frame data). Adds to [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md).

**Decision:**

- **The `:languages` settings are dictionary's whole settings**, three keys more, every key required: `:preview-languages` (the not-launched languages this environment shows, `[]` in production), `:fallback-language` and `:catalogs-path`.

  ```clojure
  :languages {:languages ["en" "de"] :default "en" :cookie-name "lang"
              :preview-languages [] :fallback-language "en" :catalogs-path "i18n"}
  ```

- **`start-app!` reads every catalog that shows** (`dictionary/load-texts`, from the classpath), before anything else starts: a missing one refuses the start. The running app holds them, `:texts`, for the server's own texts: `(dictionary/make-text (:texts running) "de" :mail/subject {:order 7})`, the language a value, the request's or the row's an email or a PDF is made for.
- **Each catalog is served where the browser fetches it**, `GET /<catalogs-path>/<language>.json`, the classpath file as it is, read once, `no-cache` (a deploy's new texts are what the next fetch gets). The page carries dictionary's settings, so parlor knows where.
- **`intl-messageformat` is one of house's npm `dependencies`**, beside react; an app lists it too (showroom's release refuses it otherwise).

**Why:** one file per language for both sides: the server reads it once and hands the browser the same bytes. Reading at the start finds a missing catalog before a person does.

**The price:** a catalog change needs a restart, in development too. Every catalog is in the server's memory twice (parsed for ICU4J, and as text to serve).

**Instead of:**
- the catalogs in the page shell's JSON: every page would carry every message
- a dynamic language bound per request (Iron Rainbow's `*locale*`): the language is a value
