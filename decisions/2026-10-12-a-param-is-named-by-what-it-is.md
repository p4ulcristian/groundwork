# A param is named by what it is

**Decision:** in a `.sql` file, a param that is a field of the data is namespaced: `:post/title`. A param that controls the query is plain: `:limit`, `:offset`, `:search`, `:since`.

**Why:** `:limit` and `:search` are not data, so they have no entity. A data field already has its name, so the app's map goes into the query unchanged: `(pg/row db insert {:post/title "Hi"})`. Reading the SQL, you see which field each value is.

**Instead of:**
- plain params only: every call renames the data's keys, and a value can land in the wrong place
- namespaced params only: controls get made-up entities like `:query/limit`
