-- Sets statement_timeout until the transaction ends, like SET LOCAL.
SELECT set_config('statement_timeout', :statement-timeout-ms, true)
