-- Hands an order to another shop, or to a driver. Its giver is the order's own shop.
INSERT INTO al_handoffs (handoff__order_id, handoff__from_shop_id, handoff__to_shop_id, handoff__driver_id)
SELECT order__id, order__shop_id, :handoff/to-shop-id, :handoff/driver-id FROM al_orders WHERE order__id = :order/id
RETURNING handoff__id
