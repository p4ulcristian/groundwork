# Names are made consistent once

**Decision:** one pass renames what is named inconsistently across Groundwork: settings keys, function and SQL file names, tables. Each rename is agreed before the pass, and both apps change in the same step. The migrations stay as they are; folding them into one baseline waits for the first real release.

**Why:** names grew part by part (the endpoints live under a `:parquery` setting; "who may" is `:access` on a route, `:permission` on an endpoint, `:scope` on a job). A framework is read more than it is written, and every later app learns the names once.

**The price:** both apps' settings change once.

**Instead of:** leaving the names; renaming part by part as each is touched.

**The renames (agreed 2026-10-18):**
- `:parquery` becomes `:endpoints`, and the list of endpoints in it `:list` (`:endpoints {:list [...] :bells … :max-asks …}`). The address stays `/parquery`.
- "Who may" is one word: `:access` on a route and on an endpoint alike (public, signed in, or an action on a scope). An endpoint's `:permission` goes.
- A job says where it runs with `:runs-in` (was `:scope`).
- One language setting: `:web :languages`. The page takes the request's language; `:page :lang` goes.
- Every yes/no setting ends in `?` (`:embeddable?`, `:keeps-for-visitor?`, and the rest).
- Kept: `:page :stylesheets` beside `:styles`, for files an app brings itself (fonts, a library's sheet).
- Inside Groundwork, no app notices: one rule for SQL query vars' `-sql` ending; SQL file names use plain verbs (insert, update, delete) when they do just that, their own verb (claim, ring) only when they mean more; `job_schedules` columns become `job_schedule__…`; `run_once` becomes `run_once_keys`.
