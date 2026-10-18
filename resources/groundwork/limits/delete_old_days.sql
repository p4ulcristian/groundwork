-- Counts from before today are done with.
DELETE FROM groundwork.limit_counts WHERE limit_count__day < (now() AT TIME ZONE 'UTC')::date
