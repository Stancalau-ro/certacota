ALTER TABLE accounts
    ALTER COLUMN metadata TYPE TEXT USING metadata::text;

ALTER TABLE discrete_transactions
    ALTER COLUMN metadata TYPE TEXT USING metadata::text;

ALTER TABLE streaming_transactions
    ADD COLUMN metadata TEXT;
