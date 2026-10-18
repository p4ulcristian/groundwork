-- An order's jobs, by name.
SELECT job__name FROM al_jobs WHERE job__order_id = :order/id ORDER BY job__name
