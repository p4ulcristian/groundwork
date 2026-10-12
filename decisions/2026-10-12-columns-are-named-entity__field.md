# Columns are named entity__field

**Decision:** a column is named `entity__field` in the database itself, and is `:entity/field` in Clojure. Parts are lowercase, words joined by single `-` (keyword) or `_` (column). Anything else throws.

**Why:** one name from the database to the browser. Nothing renames along the way, column names are unique across the database, and one search finds every use.

**The price:** a database has to be built this way; an existing one needs every column renamed. Pain for beauty.

**`__` instead of:**
- `_`: can't be split back (`post_tag_post_id`)
- `"post/title"`: quotes on every column in every query
- `$`: looks like `$1` params
