-- Sessions that ended, forgotten.
DELETE FROM groundwork.sessions WHERE session__ends_at < now()
