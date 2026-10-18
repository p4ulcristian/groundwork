-- A new note.
INSERT INTO notes (note__id, note__shop_id, note__text) VALUES (:note/id, :shop/id, :note/text)
RETURNING note__id, note__text
