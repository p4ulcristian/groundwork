-- An order's jobs, by name.
SELECT job__name FROM sh_jobs WHERE job__order_id = :order/id ORDER BY job__name
