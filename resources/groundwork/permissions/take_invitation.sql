-- Uses up a live invitation, once.
DELETE FROM groundwork.invitations
WHERE invitation__token_hash = :invitation/token-hash AND invitation__expires_at > now()
RETURNING invitation__email, invitation__scope_id, invitation__role_id
