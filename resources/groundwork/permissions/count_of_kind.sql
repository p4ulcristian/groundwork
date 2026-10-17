-- How many scopes a kind has: a kind with exactly one has no second.
SELECT count(*) AS scopes__count FROM groundwork.scopes WHERE scope__kind = :scope/kind
