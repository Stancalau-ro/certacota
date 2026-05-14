CREATE TABLE stream_tags (
    stream_id VARCHAR(255) NOT NULL,
    tag       VARCHAR(255) NOT NULL,
    PRIMARY KEY (stream_id, tag),
    CONSTRAINT fk_st_stream FOREIGN KEY (stream_id) REFERENCES streaming_transactions (stream_id)
);

CREATE TABLE discrete_transaction_tags (
    transaction_id BIGINT      NOT NULL,
    tag            VARCHAR(255) NOT NULL,
    PRIMARY KEY (transaction_id, tag),
    CONSTRAINT fk_dtt_txn FOREIGN KEY (transaction_id) REFERENCES discrete_transactions (id)
);

CREATE TABLE tag_committed_totals (
    tag                     VARCHAR(255)   NOT NULL PRIMARY KEY,
    total_debited           NUMERIC(38,18) NOT NULL DEFAULT 0,
    total_credited_recipient NUMERIC(38,18) NOT NULL DEFAULT 0,
    last_activity_at        TIMESTAMPTZ    NOT NULL,
    CONSTRAINT chk_tag_debited_nonneg   CHECK (total_debited           >= 0),
    CONSTRAINT chk_tag_credited_nonneg  CHECK (total_credited_recipient >= 0)
);

CREATE INDEX idx_tag_totals_last_activity ON tag_committed_totals (last_activity_at);
