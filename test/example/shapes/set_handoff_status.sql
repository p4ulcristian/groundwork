-- A handoff's status.
UPDATE sh_handoffs SET handoff__status = :handoff/status WHERE handoff__id = :handoff/id RETURNING handoff__status
