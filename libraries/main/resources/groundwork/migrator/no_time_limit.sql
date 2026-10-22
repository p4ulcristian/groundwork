-- A migration is slow on purpose when it has to be: no time limit until its transaction ends.
SELECT set_config('statement_timeout', '0', true) AS statement_timeout__value
