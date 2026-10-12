-- The SQL files a read endpoint has run, on any server.
SELECT endpoint_file__file FROM groundwork.endpoint_files
WHERE endpoint_file__endpoint = :endpoint-file/endpoint
