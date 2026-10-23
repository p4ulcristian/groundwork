# A library holds one idea and knows nothing of who uses it

> Restates what [A Groundwork app uses the libraries through Groundwork](2026-10-22-a-groundwork-app-uses-the-libraries-through-groundwork.md) said about a library standing alone, which that file's replacement dropped. Adds to [Libraries pass values](2026-10-22-libraries-pass-values.md).

**Decision:** each `ground-*` library holds one idea. Its code, its names and its docs know nothing about who uses it: not Groundwork, not its settings, not another library that isn't one of its own dependencies. What belongs to another idea lives in that idea's library (Parquery's ask format in `ground-parquery`, not in `ground-wire`, though Parquery sends its asks through wire). Where a library needs something from its user, it takes it as a value or a function given to it (`use-exact-decimals!`), and says only what it does with it.

**Why:** a library that knows its users can't be taken alone, and changes whenever they do. One idea per library keeps each one small enough to understand from the bottom, and keeps the rule "take what you need" true for any project.

**The price:** text and code that were convenient in one place move to the library they belong to, and a few names are said twice (once in each library's docs).

**Instead of:** libraries that mention the framework that uses them: easier to explain within Groundwork, but each library drags Groundwork's ideas wherever it goes.
