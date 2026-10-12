-- Moves a note onto a board.
UPDATE notes SET note__board_id = :board/id WHERE note__id = :note/id RETURNING note__id
