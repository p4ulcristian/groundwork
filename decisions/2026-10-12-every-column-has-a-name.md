# Every column has a name

**Decision:** a result column whose name Groundwork can't read as a key (`?column?` from an unnamed expression) throws `:groundwork.postgres/bad-column`, naming the file and the column and suggesting `… AS entity__field`. Plain names like `count` stay plain keywords. Keys inside json are not columns and keep their own rule.

**Why:** Groundwork gives every piece of data one name. A string key among keywords flows quietly into the app, and `(:something row)` turns up `nil` far from the query. Queries are tried in the REPL, so the error shows the first time a file runs.

**Instead of:** keeping the odd name as a string key. Quick queries never fail, but the stray key fails later, somewhere else.
