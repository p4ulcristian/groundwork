-- An inquiry to a shop, from anyone. No RETURNING: the sender may write it, not read it.
INSERT INTO lg_inquiries (inquiry__shop_id, inquiry__status, inquiry__text)
VALUES (:shop/id, :inquiry/status, :inquiry/text)
