-- An author with their posts nested as json.
SELECT author__name,
       (SELECT json_agg(json_build_object('post__id', post__id, 'post__title', post__title))
        FROM posts WHERE post__author_id = author__id) AS author__posts
FROM authors
WHERE author__id = :author/id
