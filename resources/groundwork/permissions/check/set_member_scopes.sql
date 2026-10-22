-- Work done for scopes rather than a person (a job that names its scope):
-- every row of them, to read and to write, until the transaction ends.
SELECT set_config('groundwork.whole_scopes', :scopes, true) AS wall__whole_scopes
