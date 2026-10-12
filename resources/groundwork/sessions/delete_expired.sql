-- Forgets sessions that ended.
DELETE FROM groundwork.sessions WHERE session__expires_at <= now()
