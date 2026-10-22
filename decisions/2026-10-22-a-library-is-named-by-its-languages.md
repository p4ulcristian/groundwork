# A library is named by its languages

> **Replaced** by [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md) (2026-10-22): languages spelled out, ground.<name> namespaces, a folder per language.

> Replaces [Code lives by where it runs](2026-10-22-code-lives-by-where-it-runs.md). Changes in part [A library's name is its door](2026-10-22-a-librarys-name-is-its-door.md) (the door's name and the keywords' namespace) and [The ground-* libraries live in one repo](2026-10-22-the-ground-libraries-live-in-one-repo.md) (where the libraries sit).

**Decision:** Groundwork is the whole repo, and its libraries live inside it, each in `libraries/ground/<name>/` (`libraries/ground/wire/`) with its own `deps.edn` and one source folder, `source-code/`. A library's code is named by the language it is written in, the same way for every library:

- `ground-<name>.cljc`: written once for both languages; the door (`ground-wire.cljc` in `source-code/ground_wire/cljc.cljc`)
- `ground-<name>.clj`: Clojure only, on the JVM (`ground-wire.clj`)
- `ground-<name>.cljs`: ClojureScript only, in the browser or on Node (`ground-wire.cljs`)

A bigger library splits further below the language: `ground-wire.cljs.time`. The keywords a library owns carry its name without the language, written out: `:ground-wire/id-text`, `:ground-session/person`.

**Why:** a namespace says in which language its code runs, by the same word as its file ending, so a name never claims more than is true: `cljs` stays true in the browser and on Node, where "browser" would not. The three read as one family. A keyword names its owner, the library, whatever file writes it.

**The price:** the endings appear twice (`cljc.cljc`), and the language is in every name that is required. Clojure tools look for `src` by default; every build names `source-code` itself. Where tests live is not decided yet.

**Instead of:**
- the side as a source root with namespaces free of it (`source-code/browser/ground/wire/time.cljs`): names stay short, but the side is seen only from the folder
- place words (`server`, `browser`, `anywhere`): reads well, but "browser" is untrue for ClojureScript on Node and "server" for a ClojureScript server
- spelled-out languages (`clojure`, `clojurescript`, `clojure-common`): plain, but the three don't read alike
- words for "both" such as shared, common, portable, hostless, isomorphic: each says something about the code, none says its language
