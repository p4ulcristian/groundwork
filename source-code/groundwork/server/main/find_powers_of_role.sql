-- What the connected role could do past the gates: whether it is a
-- superuser or may bypass row-level security, and whether it owns a table
-- in the folders the maps name (an owner can switch the gates off).
SELECT r.rolsuper OR r.rolbypassrls AS passes_gates,
       EXISTS (SELECT 1 FROM pg_tables t
               WHERE t.tableowner = current_user
                 AND t.schemaname = ANY (string_to_array(param('folders'), ','))) AS owns_tables
FROM pg_roles r
WHERE r.rolname = current_user
