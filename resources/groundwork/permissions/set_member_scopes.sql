-- Work done for scopes rather than a person: every row of them is visible, until the transaction ends.
SELECT set_config('groundwork.member_scopes', :scopes, true) AS wall__member_scopes
