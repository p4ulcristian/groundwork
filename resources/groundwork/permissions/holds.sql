-- Whether a person holds an action in a scope (or above it).
SELECT CAST(:scope/id AS uuid) IN (SELECT groundwork.scopes_holding(CAST(:person AS uuid), :action)) AS holding__yes
