-- A chore, due at :chore/run-at. With a :chore/key, a chore of that key that is
-- still remembered is kept instead, and its id returned.
WITH chore AS (
  INSERT INTO groundwork.chores (chore__kind, chore__params, chore__run_at, chore__max_attempts, chore__timeout_ms, chore__key)
  VALUES (:chore/kind, :chore/params, :chore/run-at, :chore/max-attempts, :chore/timeout-ms, :chore/key)
  ON CONFLICT (chore__key) DO NOTHING
  RETURNING chore__id
)
SELECT chore__id FROM chore
UNION ALL
SELECT chore__id FROM groundwork.chores
WHERE chore__key = :chore/key AND NOT EXISTS (SELECT 1 FROM chore)
