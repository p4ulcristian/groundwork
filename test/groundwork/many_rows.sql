-- :count numbered rows.
SELECT n AS row__n, n * 2 AS row__double
FROM generate_series(1, :count) AS n
