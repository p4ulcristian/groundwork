# Secrets are safe's wrapped secrets; an app loads its settings with safe

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): safe is groundwork-settings now (`groundwork-settings.api`).

> Changes in part [Main serves the kept errors to a monitor, and logs the browser's](server/main/2026-10-22-main-serves-the-kept-errors-and-logs-the-browsers.md) (the `:errors` token is `safe/secret`, compared by `safe/equal?`) and [Settings are required data](2026-10-12-settings-are-required-data.md) (where an app's settings come from). [The library does not need sops](2026-10-12-the-library-does-not-need-sops.md) still holds: house reads no file and runs no sops; the app does, through safe. On [safe](https://github.com/p4ulcristian/safe) v0.1.0, and cellar v0.4.3, postbox v0.1.1, attic v0.1.1, meter v0.1.3.

**Decision:** house takes [safe](https://github.com/p4ulcristian/safe), the manor's settings and secrets, server only, through `safe.api` only (`libraries.edn`, checked by the libraries test).

- **An app loads its settings with safe**, one EDN file per environment, checked against house's `settings-shape`:

  ```clojure
  (main/start-app! {:settings (safe/load {:file  "settings/production.edn"
                                          :shape main/settings-shape
                                          :env   (System/getenv)})
                    …})
  ```

- **Every secret in the settings is safe's**, refused as text: `:database :password` (cellar, `[:maybe safe/secret]`), `:metrics :token` (meter), `:attic :storage :access-key` and `:secret-key` (attic), `:postbox :sending :provider :server-token` and `:postbox :webhooks :token` (postbox), and house's own `:errors :token`. Each library reveals its own where it uses it; house reveals none: the errors route compares a request's bearer token with `safe/equal?`, in constant time, after checking the `Bearer ` prefix, which is no secret.
- **The tests make their secrets with `safe/make-secret`**, the end-to-end test's too; a test checks that text where a secret belongs is refused, each named, the text never said.
- **The sample app has development settings** that load: `test-code/house_test/sample/settings/development.edn`, its secrets in `secrets/development.sops.yaml` beside it, opened by `secrets/development-only.age-key`, an age key committed on purpose. Both are marked development-only, hold made-up values, and are used for nothing else. The end-to-end test's namespace loads the file with `safe/load` (when sops is on the PATH) and checks that `check-settings` takes it, an `#env` is filled as its type, and a secret is read and prints as its name. Paths in it are from house's root, where the sample runs.

**Why:** a settings map is printed, logged, put in ex-data and shown by the REPL's `explain-setting`; secrets in it as strings leak by every one of those. Wrapped, each prints as its name, and the text is reached only by a `reveal` in the library that uses it. One shape, `safe/secret`, across the libraries, so an app's settings file says `#secret` wherever one is due and is refused where it doesn't. The development settings are committed with their key so that anyone with house can see a whole settings file load, sops included, and so that the file can't drift from the shape unnoticed; a key that opens only made-up values gives nothing away.

**The price:** an app's settings file needs a secrets source even in development (the sample's committed key is that source for it). Two secrets with the same text are not `=`, so two settings maps are equal only when they hold the same secret values.

**Instead of:**
- strings in the settings, as before: every log of the settings is a leak
- house reading the settings file itself: safe is the one place that reads files and runs sops or op; house takes the map, as "The library does not need sops" says
- no development settings, the tests' maps only: nothing would show a settings file loading through sops, or catch the file and the shape drifting apart
- a development key kept out of the repository: the file wouldn't load on a fresh checkout, and the test would skip everywhere but one machine
