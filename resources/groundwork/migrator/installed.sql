-- The fingerprint of what migrate installed for a part, or no row.
SELECT install__fingerprint
FROM groundwork.installs
WHERE install__part = :install/part
