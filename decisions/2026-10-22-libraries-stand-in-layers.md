# Libraries stand in layers

> Replaces [Libraries pass values](2026-10-22-libraries-pass-values.md).

**Decision:** a library may use another library through its api, but only one in a lower layer. A library's layer is one above the highest layer it uses; a library that uses none is layer 0. Two libraries never use each other, directly or around a circle. `groundwork.main` is the top: it uses every library and puts them together.

Today:

| layer | libraries |
|---|---|
| 0 | wire, steps, sql |
| 1 | web (uses steps), background-jobs (uses sql, wire) |
| top | main |

A library lists what it uses in its own `deps.edn`; a test in `groundwork.main` reads every library's `deps.edn` and fails on a circle.

**Why:** the libraries are read from the bottom up: each one stands on what is below it and is understood after it. A library that needs Postgres calls `groundwork.sql` where it does so, and the call shows in its code; handing it the same functions as values from `groundwork.main` would hide that and add wiring.

**The price:** a lower library that changes its api can break the libraries above it; their tests show it.

**Instead of:** a star, where only `groundwork.main` knows two libraries and hands values between them: every library alone with plain data, but a library like background-jobs would get its database calls and shape checks as handed-in functions.
