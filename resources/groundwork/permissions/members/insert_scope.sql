-- A new place, under its parent if it has one.
INSERT INTO groundwork.scopes (scope__id, scope__kind, scope__parent_id)
VALUES (:scope/id, :scope/kind, :scope/parent-id)
