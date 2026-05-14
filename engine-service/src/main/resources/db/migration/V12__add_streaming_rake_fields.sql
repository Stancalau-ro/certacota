ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS to_account_id VARCHAR(255);
ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS rake_rate NUMERIC(38,18);
ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS platform_account_id VARCHAR(255);
ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS to_account_amount NUMERIC(38,18);
ALTER TABLE streaming_transactions ADD COLUMN IF NOT EXISTS rake_amount NUMERIC(38,18);

ALTER TABLE streaming_transactions
    ADD CONSTRAINT chk_str_rake_balanced
        CHECK (settled_amount IS NULL
            OR to_account_amount IS NULL
            OR rake_amount IS NULL
            OR settled_amount = to_account_amount + rake_amount);
