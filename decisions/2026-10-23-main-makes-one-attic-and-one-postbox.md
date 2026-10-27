# main makes one attic and one postbox, and serves the upload route and the provider's webhooks

> **Changed in part** by [The app map names its own kinds of files](2026-10-27-the-app-map-names-its-own-kinds-of-files.md) (2026-10-27): the app map's `:file-kinds` go to make-file-storage, and the files table is the one the store's settings name.

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): attic is groundwork-file-storage and postbox groundwork-email; main makes one file storage and one mailer, from the `:file-storage` and `:email` settings.

> **Changed in part** by [postbox's webhook functions run as doorman's system actor :postbox](2026-10-23-postboxs-webhook-functions-run-as-doormans-system-actor-postbox.md) (2026-10-23): the webhook functions run as the system actor `:postbox`, not on the app's role with nobody asking.

> The user's decision, 2026-10-23 (attic v0.1.0, postbox v0.1.0, two new manor libraries). Builds on [main makes one meter, hands it to every library, and serves its text](2026-10-23-main-makes-one-meter-and-serves-its-text.md) and [main makes one wire and passes it to every library](2026-10-22-main-makes-one-wire-and-passes-it-to-every-library.md).

**Decision:**

- **Two settings parts, every key required.** `:attic {:storage … :upload …}`: `:storage` is attic's `settings-shape` (the bucket, its keys, region, public URL, link expiry, timeouts, retries); `:upload` is the route's `:path` with attic's `upload-settings-shape` (`:key-prefix :allowed-content-types :max-file-bytes :max-files`). `:postbox {:sending … :webhooks …}`: `:sending` is postbox's `settings-shape` (`:provider` `:postmark` or `:log`, `:tags`, `:background-job`); `:webhooks` is its `webhook-settings-shape` (`:delivery-path :inbound-path :token :inbound-domain`). An `:upload :max-file-bytes` not below `:server :max-body-bytes` is refused at the start: http-kit holds the whole upload under that limit, so a file as large could never arrive.
- **One attic and one postbox**, made by `start-app!` on the app's log and meter (and its wire, postbox's), held by the running app, `:attic` and `:postbox`, for the app's own calls: `attic/store-file!`, `attic/make-signed-link`, `postbox/queue-email!` in a write's transaction. `stop-app!` stops the attic's client after the workers.
- **Their tables are house's**: `attic.files` and `postbox.emails` go with doorman's, backroom's and house's own into `make-ready-tables`, migrated by `migrate-app!` and granted to the app's role; both doorbells silent.
- **postbox's background job kind** (`make-background-job-kind`) runs with the app's kinds and house's own on backroom's workers.
- **The upload route** (`house.server.main.files`) is attic's `make-upload-handler` at `:attic :upload :path`, a POST outside the messages. Its `:may-upload?` is house's: doorman's session step on the request (its Origin must be `:sessions :allowed-origin`, as every changing request), then the app map's `:may-upload?`, `(fn [asker] true-or-false)`, the asker `{:person-id :visitor-id :session-flags}` as study's tools take it. Asked before the body is read.
- **The provider's webhooks** are postbox's `make-webhook-routes` at `:postbox :webhooks`' paths, outside sessions and the Origin check (the provider has neither; the token in `?token=` is the check), the app map's `:on-delivery-event {tag f}` and `:on-inbound-email {mailbox f}` handed in; each function runs in a transaction on the app's pool.
- **The app map gains three keys**, required, checked at the start (`:house.server.main/bad-app`): `:may-upload?`, `:on-delivery-event`, `:on-inbound-email` (`{}` for none).

```clojure
:attic   {:storage {:endpoint "https://<account>.r2.cloudflarestorage.com" :bucket "shop-files"
                    :access-key "…" :secret-key "…" :region "us-east-1" :public-url nil
                    :link-expiry-seconds 300 :connect-timeout-ms 10000 :request-timeout-ms 60000
                    :retries {:max-retries 4 :first-wait-ms 500 :max-wait-ms 4000}}
          :upload  {:path "/house/files" :key-prefix "uploads" :allowed-content-types #{"image/png" "image/jpeg"}
                    :max-file-bytes 10000000 :max-files 10}}
:postbox {:sending  {:provider {:name :postmark :server-token "…" :api-url "https://api.postmarkapp.com"
                                :connect-timeout-ms 10000 :request-timeout-ms 30000}   ; development: {:name :log :level :info}
                     :tags #{:orders/confirmation} :background-job {:timeout-ms 60000 :attempts 8}}
          :webhooks {:delivery-path "/postbox/delivery" :inbound-path "/postbox/inbound"
                     :token "…" :inbound-domain "in.shop.example"}}
```

**Why:** files and email are what nearly every app needs beside its rows, and each needs the app's parts (the pool, the log, the meter, the wire, the workers, the routes): house is where they meet. Who may upload is a session question, doorman's, and the app's rule on top; attic knows no sessions. The upload asks before the body is read, so a stranger can't make the server read one. The webhooks are the provider's, called from outside: no session, a token.

**The price:** every app writes both parts and the three app keys, even one that stores no file or sends no email (`:log` as the provider, a `:may-upload?` that says no, `{}` for the maps). The webhook functions run on the app's role without an asker: rows behind doorman's gates are not theirs to see.

**Instead of:**
- the upload as a message: a message is JSON; a file through it is a third larger and through the JSON parser
- attic's handler behind doorman's check of a handler (`:action`): an upload has no record or params to check an action on; the app's `(fn [asker])` says what it needs
- the parts optional: a hidden "no files, no email" default, against [every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
