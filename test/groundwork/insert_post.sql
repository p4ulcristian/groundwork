-- A new draft post.
INSERT INTO posts (post__author_id, post__title, post__meta)
VALUES (:post/author-id, :post/title, :post/meta)
RETURNING post__id, post__title, post__created_at
