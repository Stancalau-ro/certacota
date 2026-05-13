ALTER TABLE balance_audit_log
    ADD COLUMN transaction_id BIGINT,
    ADD CONSTRAINT fk_audit_dtx FOREIGN KEY (transaction_id) REFERENCES discrete_transactions(id);
