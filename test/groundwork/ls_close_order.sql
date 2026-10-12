-- Closes an order.
UPDATE lst_orders SET lst_order__status = 'closed' WHERE lst_order__id = :lst-order/id RETURNING lst_order__id
