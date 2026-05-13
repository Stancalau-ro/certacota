CREATE TABLE accounts (
    id              VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    balance         NUMERIC(38,18)  NOT NULL DEFAULT 0,
    balance_floor   NUMERIC(38,18),
    metadata        JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);
