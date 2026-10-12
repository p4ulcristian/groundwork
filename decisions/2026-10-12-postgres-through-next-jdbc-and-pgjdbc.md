# Postgres through next.jdbc and PgJDBC

**Decision:** Groundwork talks to Postgres with next.jdbc on the official PgJDBC driver.

**Why:** a library is meant to last. next.jdbc is the Clojure standard and very stable; PgJDBC is Postgres's own driver with decades of use. Every JDBC tool plugs in: connection pools, migrations, test containers, monitoring.

**The price:** some setup for jsonb and for java.time types, and less raw speed than talking the protocol directly. The network round trip dominates anyway.

**Instead of:** pg2. Faster and very Clojure-friendly, but one maintainer, still 0.1.x, and not JDBC.
