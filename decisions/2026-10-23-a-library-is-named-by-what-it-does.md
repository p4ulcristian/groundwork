# A library is named by what it does

**Decision:** every groundwork library is named by what it does, in plain words, not by a room of a house: attic is about files, cellar about Postgres, postbox about email. Each library's new name is agreed one by one before any rename; this decision renames nothing by itself.

**Why:** a name read once should say what the library is for, with no glossary. The house words had to be learned one by one (study is the REPL, safe the settings, parlor the screens), and two already misled: mailroom read as email (now bridge), showroom as a library that shows things (now browser-build).

**The price:** each renamed library changes its repository, tags, requires, keys, log events and metrics once, and so does every app that uses it. Plain words can clash with an app's own namespaces (`files`, `settings`); how a namespace avoids that is decided with the first rename.

**Instead of:**
- keeping the house words and fixing only the misfits: least churn, but the names still need learning, and the family would be half metaphor, half plain
- plain names with a family prefix (`groundwork-files`): never clashes, but long in every require and key; the `ground-*` prefix was tried and replaced on 2026-10-22
