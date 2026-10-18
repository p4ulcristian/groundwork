-- The orders of a shop.
SELECT order__id FROM al_orders WHERE order__shop_id = :shop/id ORDER BY order__note
