ALTER TABLE reports ADD COLUMN client_request_id TEXT;
CREATE UNIQUE INDEX reports_author_request ON reports(author_id, client_request_id);
