-- A service on an order, done by a partner or by nobody yet. Its shop is the order's.
INSERT INTO sh_order_services (order_service__order_id, order_service__partner_id, order_service__name)
VALUES (:order/id, :order-service/partner-id, :order-service/name)
RETURNING order_service__id
