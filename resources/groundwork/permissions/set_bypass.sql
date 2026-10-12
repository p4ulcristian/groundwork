-- This transaction runs as the system: the wall lets every row through, until the transaction ends.
SELECT set_config('groundwork.bypass', 'on', true) AS wall__bypass
