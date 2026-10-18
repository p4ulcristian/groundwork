-- Of these scopes, the ones that exist and are of a kind.
SELECT scope__id FROM groundwork.scopes WHERE scope__id = ANY(:scope/ids) AND scope__kind = :scope/kind
