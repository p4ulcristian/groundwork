-- The orders the transaction may see, narrowed to those the person may edit.
SELECT lst_order__id FROM lst_orders
WHERE lst_order__id IN (SELECT * FROM groundwork."may orders/edit on order"())
