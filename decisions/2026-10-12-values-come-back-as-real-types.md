# Values come back as real types

**Decision:** the database layer returns UUID, `java.time` and `BigDecimal` as they are. Turning them into strings happens at the wire, on the way to the browser.

**Why:** server code can compare and calculate with real types, and a UUID can't be mixed up with some other string. The browser sees the same as before.

**Instead of:** strings right out of the database.
