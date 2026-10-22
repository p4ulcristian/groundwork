# Each library keeps its own decisions

> **Changed in part** by [A library is a folder with its own deps.edn](2026-10-22-a-library-is-a-folder-with-its-own-deps.md) (2026-10-22): a library's decisions are in libraries/<name>/decisions/.

> **Changed in part** by [Libraries are folders in one tree, their edges declared](2026-10-22-libraries-are-folders-in-one-tree.md) (2026-10-22): one source tree, libraries as folders in source-code/groundwork/, edges in libraries.edn.

> **Changed in part** by [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md) (2026-10-22): a library's decisions are in `libraries/<name>/decisions/`.

> Adds to [The ground-* libraries live in one repo](2026-10-22-the-ground-libraries-live-in-one-repo.md).

**Decision:** each `ground-*` library keeps its decisions in its own folder, `libs/ground-*/decisions/`, with its own index. Decisions about the whole (what Groundwork is, one door, the Clojure way, how the libraries are laid out) stay in the top `decisions/`. When the libraries are cut out, every existing decision file moves to the library it is about, and the links between files are rewritten; a file about several libraries stays at the top. The rules of the top README (one file per decision, top notes, nothing rewritten but the top note) hold in every folder.

**Why:** a library can be used alone, so it carries its spec with it. A library's decisions sit next to its code, where whoever changes it reads them.

**The price:** a one-time move of about 90 files and their links. A question that crosses libraries means reading two folders.

**Instead of:**
- one `decisions/` with a subfolder per library: everything in one place, but a library used alone doesn't carry its spec
- flat as today, the index grouped by library: nothing moves, but the folder keeps growing and a library's spec isn't with its code
