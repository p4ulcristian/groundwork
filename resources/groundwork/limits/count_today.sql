-- One more call today against a limit; the count after it.
INSERT INTO groundwork.limit_counts AS c (limit_count__limit_id, limit_count__caller, limit_count__day, limit_count__n)
VALUES (:limit-count/limit-id, :limit-count/caller, (now() AT TIME ZONE 'UTC')::date, 1)
ON CONFLICT (limit_count__limit_id, limit_count__caller, limit_count__day)
DO UPDATE SET limit_count__n = c.limit_count__n + 1
RETURNING limit_count__n
