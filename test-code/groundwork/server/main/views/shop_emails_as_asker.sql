CREATE VIEW shop_emails_as_asker WITH (security_invoker = true) AS SELECT shop_email__address, shop_email__shop_id FROM shop_emails
