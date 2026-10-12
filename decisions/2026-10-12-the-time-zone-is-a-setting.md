# The time zone is a setting

**Decision:** `pg/start` requires `:time-zone`, a zone name like `"UTC"` or `"Europe/Budapest"`. Every new connection runs `SET TIME ZONE` with it, so the session's zone is the config's, whatever machine the app runs on. A name with anything but letters, digits and `_ + - /` throws at start; Postgres checks the rest.

**Why:** the session zone decides how Postgres reads a time without an offset (a `LocalDateTime` going into `timestamptz`) and how it shows times. PgJDBC sends the JVM's zone when it connects, so the same value was stored an hour apart on two machines. Settings are required data, so the zone is in the config where it can be seen.

**The price:** a `SET` on each new connection, not each query. It can't go through PgJDBC's `options`, because the zone PgJDBC sends when it connects wins over it.

**Instead of:** the JVM's zone (changes with the machine, invisible in the config), or pinning UTC inside Groundwork (a hidden default).
