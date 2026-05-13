CREATE TABLE discrete_transactions (
    id              BIGSERIAL       PRIMARY KEY,
    account_id      VARCHAR(255)    NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    amount          NUMERIC(38,18)  NOT NULL,
    metadata        JSONB,
    idempotency_key VARCHAR(255),
    posted_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_dtx_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_dtx_type CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_dtx_amount CHECK (amount > 0)
);

CREATE INDEX idx_dtx_account_id ON discrete_transactions(account_id);
