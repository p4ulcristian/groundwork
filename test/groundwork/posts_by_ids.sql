-- Posts whose id is in the list.
SELECT post__id FROM posts WHERE post__id = ANY(:post/ids) ORDER BY post__title
