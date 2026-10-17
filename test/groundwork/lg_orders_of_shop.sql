-- A shop's orders.
SELECT order__id FROM lg_orders WHERE order__shop_id = :shop/id ORDER BY order__id
