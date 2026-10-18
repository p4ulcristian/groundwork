-- Remembers that a read endpoint runs a SQL file.
INSERT INTO groundwork.endpoint_files (endpoint_file__endpoint, endpoint_file__file)
VALUES (:endpoint-file/endpoint, :endpoint-file/file)
ON CONFLICT DO NOTHING
