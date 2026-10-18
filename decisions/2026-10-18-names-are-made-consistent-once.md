# Names are made consistent once

**Decision:** one pass renames what is named inconsistently across Groundwork: settings keys, function and SQL file names, tables. Each rename is agreed before the pass, and both apps change in the same step. The migrations stay as they are; folding them into one baseline waits for the first real release.

**Why:** names grew part by part (the endpoints live under a `:parquery` setting; "who may" is `:access` on a route, `:permission` on an endpoint, `:scope` on a job). A framework is read more than it is written, and every later app learns the names once.

**The price:** both apps' settings change once.

**Instead of:** leaving the names; renaming part by part as each is touched.
