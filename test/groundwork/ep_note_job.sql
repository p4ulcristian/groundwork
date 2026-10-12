-- Writes a job's note.
UPDATE jobs SET job__note = :job/note WHERE job__id = :job/id
RETURNING job__id, job__note
