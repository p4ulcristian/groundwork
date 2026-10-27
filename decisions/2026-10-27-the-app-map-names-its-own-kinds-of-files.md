# The app map names its own kinds of files; the files table is the store's settings'

> The user's decision, 2026-10-26, from Iron Rainbow's file storage: groundwork-file-storage v0.2.0's [The app's own kinds are data](https://github.com/p4ulcristian/groundwork-file-storage/blob/v0.2.0/decisions/2026-10-26-the-apps-own-kinds-are-data.md) and [One files table per store](https://github.com/p4ulcristian/groundwork-file-storage/blob/v0.2.0/decisions/2026-10-26-one-files-table-per-store.md), taken by groundwork v7.0.0. Changes in part [main makes one attic and one postbox](2026-10-23-main-makes-one-attic-and-one-postbox.md): the files table was `groundwork_file_storage.files`, a fixed value.

**Decision:** groundwork pins groundwork-file-storage v0.2.0. The app map has `:file-kinds`, required, groundwork-file-storage's `:kinds` (`{:content-type :extension :recognize :served-as}` each, `[]` for none), which `make-file-storage` is handed with the app's log and meter. The one store's files table is the one its settings name (`:file-storage :storage :table`, `:groundwork-file-storage/<name>`), migrated, granted and checked with groundwork's tables (`make-groundwork-tables`, a function of the settings now, in place of the `groundwork-tables` value). The `:file-storage :storage` settings are groundwork-file-storage's new ones, every key written out: `:table`, `:cache-purge`, `:longest-link-seconds` (in place of `:link-expiry-seconds`), `:head-bytes`, `:deletes?`. `find-file` and `list-files` take the store.

**Why:** what a file's bytes may be is the app's to say, and the store's table is its setting: groundwork passes both through.

**Instead of:**
- **the kinds in the settings:** a kind's `:recognize` is a function, which an EDN file can't hold
