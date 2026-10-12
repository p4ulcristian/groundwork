-- The notes of a shop.
SELECT note__id, note__text FROM notes WHERE note__shop_id = :shop/id ORDER BY note__text
