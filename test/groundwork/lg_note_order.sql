-- Writes an order's note.
UPDATE lg_orders SET order__note = :order/note WHERE order__id = :order/id RETURNING order__id
