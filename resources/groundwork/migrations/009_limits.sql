-- Per-day caps: how many calls a caller made today against one limit.
CREATE TABLE groundwork.limit_counts (
  limit_count__limit_id text NOT NULL,
  limit_count__caller   text NOT NULL,
  limit_count__day      date NOT NULL,
  limit_count__n        integer NOT NULL,
  PRIMARY KEY (limit_count__limit_id, limit_count__caller, limit_count__day)
);
