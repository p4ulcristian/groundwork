-- Closes every open order the wall lets this transaction change.
UPDATE lg_orders SET order__status = 'closed' WHERE order__status = 'open' RETURNING order__id
