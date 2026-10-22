-- Signing out: this session ends at once.
DELETE FROM groundwork.sessions WHERE session__fingerprint = :session/fingerprint
