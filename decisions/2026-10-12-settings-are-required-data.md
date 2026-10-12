# Settings are required data

**Decision:** `pg/start` has no defaults. Every setting (`:host :port :database :user :password :pool-size :statement-timeout-ms`) must be in the map; a missing one throws at start, naming each one missing. `nil` and `:none` are choices, written out. The hooks stay optional: leaving one out adds nothing.

**Why:** the app's config tells the whole story; nothing important lives inside the library. Forgetting a safety setting is impossible, because start refuses. Config stays plain data that can be compared between environments.

**The price:** every app repeats values that are nearly always the same, like port 5432. The library doesn't pick a good value for you, so the README recommends one with its reason. HikariCP's own tuning (keepalive, max lifetime, connection wait) keeps Hikari's defaults; Groundwork only requires what it exposes.

**Instead of:** defaults the app can override. A good value with no thought, but the value in effect is invisible in the app's code.
