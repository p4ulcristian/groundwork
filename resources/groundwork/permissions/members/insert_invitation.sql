-- An invitation, valid for some days.
INSERT INTO groundwork.invitations
  (invitation__email, invitation__scope_id, invitation__role_id, invitation__token_hash,
   invitation__invited_by, invitation__expires_at)
VALUES (lower(:invitation/email), :invitation/scope-id, :invitation/role-id, :invitation/token-hash,
        :invitation/invited-by, now() + make_interval(days => CAST(:days AS integer)))
