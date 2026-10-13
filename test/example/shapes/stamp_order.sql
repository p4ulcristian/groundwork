-- Writes an order's note.
UPDATE sh_orders SET order__note = :order/note WHERE order__id = :order/id RETURNING order__note
