# The transaction hook runs only in transactions

**Decision:** `:on-transaction` runs right after `BEGIN` inside `with-transaction`, and nowhere else.

**Why:** running it for every query outside a transaction would wrap each one in an extra `BEGIN`/`COMMIT`. With row-level security, a query outside a transaction has no settings and sees nothing: it fails closed.
