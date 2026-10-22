-- A scope: the app's own row (a shop), known to access by its kind and id.
INSERT INTO groundwork.scopes (scope__kind, scope__id) VALUES (:scope/kind, :scope/id)
