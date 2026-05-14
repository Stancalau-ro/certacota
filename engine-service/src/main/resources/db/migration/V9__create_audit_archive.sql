CREATE SCHEMA IF NOT EXISTS audit_archive;

CREATE TABLE audit_archive.balance_audit_log (
    id              BIGINT          NOT NULL,
    account_id      VARCHAR(255)    NOT NULL,
    operation       VARCHAR(50)     NOT NULL,
    amount          NUMERIC(38,18)  NOT NULL,
    balance_before  NUMERIC(38,18)  NOT NULL,
    balance_after   NUMERIC(38,18)  NOT NULL,
    idempotency_key VARCHAR(255),
    transaction_id  BIGINT,
    recorded_at     TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_audit_archive PRIMARY KEY (id)
);

CREATE INDEX idx_arch_audit_account_id ON audit_archive.balance_audit_log(account_id);
CREATE INDEX idx_arch_audit_recorded_at ON audit_archive.balance_audit_log(recorded_at);
