-- A design, its visitor's or its person's: whoever is asking.
INSERT INTO al_designs (design__customer_id, design__visitor_id, design__name)
VALUES (:design/customer-id, :design/visitor-id, :design/name)
RETURNING design__id
