# A list param takes the type Postgres expects

**Decision:** a list param (a vector, a seq or a set) goes in as the type Postgres expects for its place in the SQL: a SQL array (`uuid[]`, `text[]`…) or json/jsonb. The first time a file gets a list, Groundwork asks Postgres the type of every param in it and keeps the answer with the parsed file. A file that never gets a list is never asked.

**Why:** Postgres already knows from the SQL what each param must be. `WHERE post__id = ANY(:post/ids)` reads plainly. An empty list works with no cast, because the element type comes from Postgres, not from guessing at the values. A row read with an array or a json list column goes straight back in. pg2 works this way; this brings it to JDBC.

**The price:**
- more inside Groundwork: the type lookup and its cache
- one extra small round trip the first time a file gets a list
- a param with nothing around it to tell its type (`SELECT :x`) needs a cast in the file, `:x::int[]`; without one it throws, naming the param and the file
- a column whose type a migration changes keeps the cached type until the file changes or the app restarts; it fails loudly, never silently

**Instead of:**
- always a SQL array: element types guessed from the values, an empty list needs a cast in every file, json lists need wrapping
- always jsonb: `ANY` needs `jsonb_array_elements_text` plumbing in every query that takes a list
- the caller wraps every list, `(pg/array v)` or `(pg/json v)`: rows stop going straight back in
