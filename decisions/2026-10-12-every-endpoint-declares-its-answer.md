# Every endpoint declares its answer

**Decision:** every endpoint declares the shape of what it may send back, as a malli schema. An endpoint without one doesn't start. An answer that doesn't fit its shape isn't sent: the browser gets a generic error, and the details go to the log.

**Why:** the shape stops what shouldn't leave the server (an extra column, a password hash) from reaching the browser, and it is what gives types back on the wire: dates, ids and decimals travel as text in JSON, and the shape says what each one is.

**The price:** a shape has to be kept up to date with its query; a mismatch fails loudly the first time the endpoint runs.

**Instead of:** optional shapes. An endpoint without one would send whatever its query returns, untyped, and that's where leaks and string-instead-of-UUID values hide.
