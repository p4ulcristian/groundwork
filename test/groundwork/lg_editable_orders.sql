-- The orders the person may edit, of those the wall shows.
SELECT order__id FROM lg_orders
WHERE order__id IN (SELECT * FROM groundwork."may orders/edit on order"())
ORDER BY order__id
