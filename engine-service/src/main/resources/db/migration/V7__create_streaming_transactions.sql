CREATE TABLE streaming_transactions (
    id              BIGSERIAL        NOT NULL,
    stream_id       VARCHAR(255)     NOT NULL,
    account_id      VARCHAR(255)     NOT NULL,
    status          VARCHAR(20)      NOT NULL DEFAULT 'ACTIVE',
    rate_per_second NUMERIC(38,18)   NOT NULL,
    minimum_amount  NUMERIC(38,18),
    increment       NUMERIC(38,18),
    started_at      TIMESTAMPTZ      NOT NULL,
    stopped_at      TIMESTAMPTZ,
    settled_amount  NUMERIC(38,18),
    reason          VARCHAR(255),
    idempotency_key VARCHAR(255),
    CONSTRAINT pk_streaming_transactions PRIMARY KEY (id),
    CONSTRAINT uq_stream_id UNIQUE (stream_id),
    CONSTRAINT fk_str_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_str_status CHECK (status IN ('ACTIVE', 'SETTLED', 'ERROR')),
    CONSTRAINT chk_str_rate CHECK (rate_per_second > 0)
);

CREATE INDEX idx_str_account_id ON streaming_transactions(account_id);
CREATE INDEX idx_str_status ON streaming_transactions(status);
CREATE UNIQUE INDEX uq_str_idempotency ON streaming_transactions(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
