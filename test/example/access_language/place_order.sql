-- A new order in a shop.
INSERT INTO al_orders (order__id, order__shop_id, order__note) VALUES (gen_random_uuid(), :order/shop-id, :order/note)
RETURNING order__id
