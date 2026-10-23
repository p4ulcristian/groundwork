# The plain names ship in one pass

> Follows [Each library's plain name](2026-10-23-each-librarys-plain-name.md) and [A library's Postgres schema carries its full name](2026-10-23-a-librarys-postgres-schema-carries-its-full-name.md).

**Decision:** when the renames are made, all fifteen libraries change in one pass (thirteen new names; bridge and browser-build keep theirs but move to `groundwork-` repositories and namespaces): repositories, folders, namespaces, keys, log events, metrics and schemas, then one `tools/bump` cascade, then Iron Rainbow's groundwork-1.0 branch once. The pass starts only when it is called for; this decision schedules nothing.

**Why:** an app changes once, with one breaking release, as [names are made consistent once](2026-10-18-names-are-made-consistent-once.md) already says; there is no stretch where `doorman` sits beside `groundwork-access`.

**The price:** one large change to check at once, and nothing works between its start and its end.

**Instead of:** library by library: small steps checked alone, but Iron Rainbow's branch breaks thirteen times and the names stay mixed in between.
