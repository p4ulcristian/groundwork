-- Every action the person holds, and where: for the browser's "can I?".
SELECT s AS grant__scope_id, a AS grant__action
FROM unnest(CAST(:actions AS text[])) AS a,
     LATERAL groundwork.scopes_holding(CAST(:person AS uuid), a) AS s
