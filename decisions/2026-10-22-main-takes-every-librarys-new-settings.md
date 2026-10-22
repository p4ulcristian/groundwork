# main takes every library's new settings: the pool's, a migration's limits, the workers', the listener's, the watcher's

> Changes in part [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): Groundwork takes the libraries' releases that made their last fixed values settings, and main hands each one on from its settings.

**Decision:** Groundwork takes wire v0.1.4, cellar v0.1.3, backroom v0.1.4, mailroom v0.1.4, doorman v0.1.3, doorbell v0.1.2 and showroom v0.1.1, and every value they now require is a setting of the app's, every key required, none filled in by main:

- **`:database`** is cellar's pool settings, 20 keys: cellar v0.1.2's ten new ones (`:connection-wait-ms`, `:connection-check-wait-ms`, `:connection-max-lifetime-ms`, `:connection-keepalive-every-ms`, `:leak-warning-after-ms`, `:connect-timeout-seconds`, `:read-timeout-seconds`, `:tcp-keepalive?`, `:lock-timeout-ms`, `:idle-in-transaction-timeout-ms`) and `:tls-root-cert`, written out, nil for the Java runtime's certificates.
- **`:migration`**, new: the time limits each part of a migration runs under, cellar's `migrate!` options, used by `migrate-app!` and by the start that migrates in development:

  ```clojure
  :migration {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
              :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}
  ```

- **`:background-jobs`** is backroom's settings, 8 keys: `:retry-first-wait-ms`, `:retry-wait-factor`, `:retry-max-wait-ms`, `:stuck-after-time-limit-ms`, `:forget-done-after-ms` and `:stop-wait-ms` beside `:workers` and `:poll-ms`.
- **`:doorbell`** holds doorbell's two parts: `:store`, the browser's question store's settings, which the page carries as before (under `"doorbell"`), and `:listening`, the server's listener's: `:batch-window-ms`, `:check-stopped-every-ms`, `:reconnect-after-ms`, `:stop-wait-ms`.
- **`:browser-build`** is `:built`, or `{:watch options}`: showroom's `start-watching!` options but `:entry` and `:files-folder`, which main takes from the app's `:browser-entry` and `:page :files :folder` (an options map that writes them is refused). showroom checks the options, every key required, right after the settings and before anything starts. The `:browser-release` alias writes showroom's release options in its `:exec-args`.
- **Table maps** write out `:indexes`; `:gates` come from doorman's `put-gates-on-tables` (nil on a table without sentences) and `:on-change` from doorbell's `put-doorbell-on-tables` (nil on a silent table); a column says only the options it has (cellar's [A column says only the options it has](https://github.com/p4ulcristian/cellar/blob/main/decisions/2026-10-22-a-column-says-only-the-options-it-has.md)).

**Why:** the libraries took these values out of their code so an app sees each one; a framework that filled them in again would hide them one layer up. The migration's limits are their own part, not `:database`'s: they are the deploy's, and the pool's settings are the running app's (cellar's decision). `:doorbell`'s two parts are nested, not merged, because the page carries `:store` to the browser and the browser's store refuses a key it doesn't know. The watch options sit inside `:browser-build`, so watching without options, or options while built, can't be written.

**The price:** an app's settings grow by 10 keys in `:database`, 6 in `:background-jobs`, 4 in `:doorbell` and the 2 maps of `:migration`; `:doorbell` moves its old keys under `:store`; a development config writes 12 watch options.

**Instead of:**
- the migration's limits in `:database`: cellar's settings are closed, and a deploy and a running app use them differently
- `:doorbell`'s six keys in one map, split by main: the page would need a list of which keys are the browser's, kept beside doorbell's shapes
- a `:browser-watch` key beside `:browser-build :watch`: two keys that must agree, and a check that they do
