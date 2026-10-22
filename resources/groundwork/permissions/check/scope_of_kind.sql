-- Whether a scope exists and is of a kind.
SELECT scope__id FROM groundwork.scopes WHERE scope__id = CAST(:scope/id AS uuid) AND scope__kind = :scope/kind
