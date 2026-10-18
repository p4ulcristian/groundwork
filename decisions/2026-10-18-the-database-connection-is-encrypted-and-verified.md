# The database connection is encrypted and verified

**Decision:** `:db :tls` is a required setting: `:verified` (encrypted, the server's certificate and name checked against the system's trusted certificates, or a certificate file the app names) or `:off`. In production Groundwork refuses `:off` unless the database is on the same machine.

**Why:** passwords and every row travel between the app and the database. Encrypted only stops listeners, not someone pretending to be the database; verified stops both. Said in the settings, it can't be forgotten or hidden in a driver's config file.

**The price:** one more required key for every app.

**Instead of:** an optional setting that defaults to off; leaving it to the database driver's own config file.
