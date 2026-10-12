-- Newest published posts with their author.
SELECT post__id, post__title, post__created_at, author__name
FROM posts
JOIN authors ON author__id = post__author_id
WHERE post__published
ORDER BY post__created_at DESC
LIMIT :limit
