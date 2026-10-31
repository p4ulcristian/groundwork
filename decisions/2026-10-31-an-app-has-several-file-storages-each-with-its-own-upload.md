# An app has several file storages, each with its own upload route or none

> The user's decision, 2026-10-31 (Paul approved the design), from Iron Rainbow's files. Changes in part [main makes one attic and one postbox](2026-10-23-main-makes-one-attic-and-one-postbox.md) (one file storage, one upload route), [The app map names its own kinds of files](2026-10-27-the-app-map-names-its-own-kinds-of-files.md) (the one store's files table) and [Handlers get the app's things handed in](2026-10-27-handlers-get-the-apps-things-handed-in.md) (the `:file-storage` part).

**Decision:**

- **The settings name the storages.** `:file-storage` is `{:storages {<name> {:storage … :upload …} …}}`, every key required, at least one storage. `:storage` is groundwork-file-storage's `settings-shape` (the bucket, its keys, its files table `:table` …); `:upload` is nil (no upload route for that storage) or the route's `:path` with groundwork-file-storage's `upload-settings-shape` (`:key-prefix :allowed-content-types :max-file-bytes :max-files`). Across the storages, refused at the start and by `migrate-app!` (`:groundwork.server.main/bad-settings`, every problem at once, each storage named): two storages with one files table, two uploads at one path, an upload whose `:max-file-bytes` is not below `:bridge :max-body-bytes`.
- **Each storage is groundwork-file-storage's**, made by `make-file-storage` from its `:storage` settings and the app map's `:file-kinds` (the same for every storage), on the app's log and meter; its files table (`groundwork_file_storage.<its :table's name>`) migrated, granted and checked with groundwork's tables (`make-groundwork-tables`, by the storages' names in order). `stop-app!` stops every storage's clients.
- **The running app holds `:file-storages`**, name → storage, in place of `:file-storage`; the app's `:make-given` gets the same map as `:file-storages`, so a handler or a job reaches a storage by its name (`(:invoices (:file-storages given))`).
- **Each storage with an upload has a route of its own** at its `:path` (`groundwork.server.main.files`), storing into that storage: groundwork-access's session step once, then the app map's `:may-upload?`, now `(fn [asker storage-name] true-or-false)`, so the app says who may upload to which. A storage whose `:upload` is nil has no route.
- Email and every other part are as they were.

```clojure
:file-storage {:storages {:public-files  {:storage {… :table :groundwork-file-storage/public-files}
                                          :upload  {:path "/groundwork/files/public" :key-prefix "shops/logos"
                                                    :allowed-content-types #{"image/png" "image/jpeg"}
                                                    :max-file-bytes 10000000 :max-files 10}}
                          :private-files {:storage {… :table :groundwork-file-storage/private-files}
                                          :upload  {:path "/groundwork/files/private" :key-prefix "orders/photos" …}}
                          :catalog-files {:storage {… :table :groundwork-file-storage/catalog-files}
                                          :upload  nil}}}

:may-upload? (fn [{:keys [person-id]} storage-name]
               (and (some? person-id) (contains? #{:public-files :private-files} storage-name)))
```

**Why:** Iron Rainbow keeps public, private and catalog files apart: a public bucket behind a CDN, a private one read through signed links, the catalog's own table that only catalog rows name (groundwork-file-storage's [one files table per store](https://github.com/p4ulcristian/groundwork-file-storage/blob/v0.2.0/decisions/2026-10-26-one-files-table-per-store.md)). With one composer storage the app made the other two itself, with its own settings and its own migration of their tables. Now there is one way to make a store for every app: named in the settings, made, migrated, granted and stopped by the composer, handed in by name. Each upload is its own route because each storage takes its own files (its prefix, its content types, its sizes) and the app's rule for who may upload differs by storage.

**The price:** every app rewrites its `:file-storage` settings under `:storages` and names each storage, and its `:may-upload?` takes the storage's name; code that read `(:file-storage given)` or `(:file-storage running)` reads `(:<name> (:file-storages …))`. A storage with no upload still writes `:upload nil`.

**Instead of:**
- **one composer storage and the app making the rest itself:** two ways to make a store, the app's without the composer's checks, migration, grants or stopping
- **one upload route for all the storages, the storage named in the request:** one set of upload settings for files that differ, and the browser choosing where a file goes
- **`:upload` left out for no route:** a hidden default, against [every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
