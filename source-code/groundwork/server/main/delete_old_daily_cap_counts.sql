-- The counts of the days before this one (UTC): no cap reads them again.
DELETE FROM groundwork.daily_cap_counts WHERE daily_cap_count__day < param('daily-cap-count/day')
