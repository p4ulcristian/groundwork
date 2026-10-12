-- How many posts have an id in the list.
SELECT count(*) FROM posts WHERE post__id = ANY(:post/ids)
