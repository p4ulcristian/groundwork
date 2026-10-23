-- One more call counted, on a row lock_daily_cap_count.sql locked.
UPDATE groundwork.daily_cap_counts
SET daily_cap_count__count = daily_cap_count__count + 1
WHERE daily_cap_count__message_name = :daily-cap-count/message-name
  AND daily_cap_count__counted_for = :daily-cap-count/counted-for
  AND daily_cap_count__day = :daily-cap-count/day
