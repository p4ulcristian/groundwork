-- A price under an agreement. Its two scopes aren't named: they are the agreement's.
INSERT INTO al_price_sheets (price_sheet__agreement_id, price_sheet__cents)
VALUES (:agreement/id, :price-sheet/cents)
RETURNING price_sheet__id
