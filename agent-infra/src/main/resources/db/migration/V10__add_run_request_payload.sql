ALTER TABLE run_record ADD COLUMN IF NOT EXISTS request_message TEXT;
ALTER TABLE run_record ADD COLUMN IF NOT EXISTS request_references_json TEXT;
ALTER TABLE run_record ADD COLUMN IF NOT EXISTS request_attachments_json TEXT;
