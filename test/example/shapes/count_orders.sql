-- How many orders the wall shows of a shop.
SELECT count(*)::int AS orders__count FROM sh_orders WHERE order__shop_id = :shop/id
