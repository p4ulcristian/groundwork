-- A payout for an order to a payee: a shop's slice of what the order brought.
-- The payee's shop owns the row; the order's shop may be another.
INSERT INTO lg_payouts (payout__shop_id, payout__order_id, payout__cents)
SELECT :payout/payee-id, order__id, :payout/cents FROM lg_orders WHERE order__id = :order/id
