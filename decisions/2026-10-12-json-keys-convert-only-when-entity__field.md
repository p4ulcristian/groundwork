# JSON keys convert only when shaped entity__field

**Decision:** inside json/jsonb, a key like `post__id` becomes `:post/id`. Every other key stays a string.

**Why:** JSON holds user data too. A key like `"First Name"` must not fail a query or be changed.
