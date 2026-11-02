# The database's provider's extensions are the place's settings: :provider-extensions

> The user's decision, 2026-11-02, from Iron Rainbow's production flip: Neon put `pg_session_jwt` in prod's database, owned by Neon's admin, and the deploy's migrate stopped at it. On groundwork-postgres v0.5.2's [The database's provider's extensions are declared, and let be](https://github.com/p4ulcristian/groundwork-postgres/blob/main/decisions/2026-11-02-the-providers-extensions-are-declared-and-let-be.md). v8.0.10.

**Decision:** the settings have a part `:provider-extensions`, required, the names of the extensions the place's database's provider put in it and keeps, `[]` where it put none. migrate (`migrate-app!`, and the development start's) and the start's check that the database is current both declare it to groundwork-postgres as its `:provider-extensions`, beside the app map's `:extensions`.

```clojure
;; prod's database part, on Neon
:provider-extensions ["pg_session_jwt"]
;; a development Postgres, stage's own cluster, the tests'
:provider-extensions []
```

**Why:** the same app runs on databases whose providers differ: prod on Neon holds `pg_session_jwt`, a development Postgres or a test's holds nothing. Which extensions a database's provider keeps is a fact of the place, as its host and port are (`:postgres`), so each place's settings say it; the app map says what the app's code needs, the same everywhere.

**Instead of:**
- **beside `:extensions` in the app map:** the app map is the same on every place; prod's list would be every place's, or the app would build its map from its settings to change one key
- **a key inside `:postgres`:** `:postgres` is groundwork-postgres's pool settings, handed to `start-pool!` as they are (by the composer twice, by an app wherever it starts a pool of its own, the owner's for `migrate-app!` among them); every one of those would have to take the key out first
- **inside `:migration`:** that part is the time limits of a migration's two parts, the same on every place; and the start's check reads the list too, not only the migration
