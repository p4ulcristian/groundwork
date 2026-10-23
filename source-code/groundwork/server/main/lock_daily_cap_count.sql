-- A handler's count for one asker (or everyone) on one UTC day, made at 0
-- when missing, its row locked until the counting transaction ends, so two
-- calls at once count one after the other.
INSERT INTO groundwork.daily_cap_counts AS counted
  (daily_cap_count__message_name, daily_cap_count__counted_for, daily_cap_count__day, daily_cap_count__count)
VALUES (:daily-cap-count/message-name, :daily-cap-count/counted-for, :daily-cap-count/day, 0)
ON CONFLICT (daily_cap_count__message_name, daily_cap_count__counted_for, daily_cap_count__day) DO UPDATE
SET daily_cap_count__count = counted.daily_cap_count__count
RETURNING daily_cap_count__count
