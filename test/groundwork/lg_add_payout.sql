-- A payout for an order, in the order's shop.
INSERT INTO lg_payouts (payout__shop_id, payout__order_id, payout__cents)
SELECT order__shop_id, order__id, :payout/cents FROM lg_orders WHERE order__id = :order/id
RETURNING payout__id
