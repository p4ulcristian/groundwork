-- A reply under a ticket, from anyone. No RETURNING: the sender may write it, not read it.
INSERT INTO lg_replies (reply__ticket_id, reply__shop_id, reply__text)
VALUES (:ticket/id, :shop/id, :reply/text)
