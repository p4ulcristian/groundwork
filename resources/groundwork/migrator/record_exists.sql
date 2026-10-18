-- Whether Groundwork's record of migrations is there: the first migrate (or
-- a start in development) makes it.
SELECT to_regclass('groundwork.migrations') IS NOT NULL AS migration_record__exists
