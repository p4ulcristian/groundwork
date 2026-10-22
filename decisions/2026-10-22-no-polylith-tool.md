# No Polylith tool

> Adds to [A library is a folder with its own deps.edn](2026-10-22-a-library-is-a-folder-with-its-own-deps.md) and [Libraries stand in layers](2026-10-22-libraries-stand-in-layers.md).

**Decision:** Groundwork keeps its own layout (`libraries/<name>/` with its own `deps.edn`) and doesn't use Polylith's `poly` tool, its `workspace.edn`, or its `components/`, `bases/` and `projects/` folders.

**Why:** in Polylith a brick doesn't name the bricks it uses; they meet only on a project's classpath. Groundwork's libraries list what they use in their own `deps.edn`, which is what lets each be tested alone in its folder and taken alone from git with `:deps/root`. What the tool would add is covered otherwise: no circles by the layers test, "others call only the api" by a test of the same kind when wanted, and testing only what changed isn't needed while the tests are fast.

**The price:** no `poly info` overview and no incremental test runs; each check the tool gives for free is a small test written here.

**Instead of:**
- the poly tool on Polylith's folders: its checks and incremental tests, but libraries that can't stand alone
