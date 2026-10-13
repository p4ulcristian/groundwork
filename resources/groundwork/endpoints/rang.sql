-- The bells rung in this transaction so far: one JSON line each, or 'any'.
SELECT current_setting('groundwork.rang', true) AS bell__rang
