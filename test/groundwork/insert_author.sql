-- A new author.
INSERT INTO authors (author__name)
VALUES (:author/name)
RETURNING author__id, author__name
