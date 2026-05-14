ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS to_account_id VARCHAR(255);
ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS rake_rate NUMERIC(38,18);
ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS platform_account_id VARCHAR(255);
