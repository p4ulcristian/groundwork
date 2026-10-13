-- The prices under an agreement.
SELECT price_sheet__cents FROM sh_price_sheets WHERE price_sheet__agreement_id = :agreement/id ORDER BY price_sheet__cents
