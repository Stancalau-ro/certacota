ALTER TABLE discrete_transactions
    ADD CONSTRAINT uq_dtx_idempotency_key UNIQUE (idempotency_key);
