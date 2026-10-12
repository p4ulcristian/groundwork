-- Publishes a draft. Does nothing if it is already published.
UPDATE posts
SET post__published = true
WHERE post__id = :post/id AND NOT post__published
