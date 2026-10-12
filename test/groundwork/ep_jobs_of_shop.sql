-- The jobs of a shop.
SELECT job__id FROM jobs WHERE job__shop_id = :shop/id ORDER BY job__id
