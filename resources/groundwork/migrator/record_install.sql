-- Remembers what was installed for a part: the fingerprint of its statements.
INSERT INTO groundwork.installs (install__part, install__fingerprint)
VALUES (:install/part, :install/fingerprint)
ON CONFLICT (install__part)
DO UPDATE SET install__fingerprint = EXCLUDED.install__fingerprint, install__installed_at = now()
