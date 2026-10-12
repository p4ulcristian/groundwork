-- Which of these sessions (key hashes in hex) are still live.
SELECT encode(session__key_hash, 'hex') AS session__hash
FROM groundwork.sessions
WHERE encode(session__key_hash, 'hex') = ANY (:session/hashes)
  AND session__expires_at > now()
