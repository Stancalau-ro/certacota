#!/bin/sh
set -e

echo "perf-db seed.sh starting: ACCOUNTS=${ACCOUNTS}, TXN_PER_ACCOUNT=${TXN_PER_ACCOUNT}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE TABLE IF NOT EXISTS stress_accounts (
        id VARCHAR(36) PRIMARY KEY,
        balance NUMERIC(36,18) NOT NULL,
        created_at TIMESTAMPTZ DEFAULT NOW()
    );

    INSERT INTO stress_accounts (id, balance, created_at)
    SELECT
        'stress-acc-' || LPAD(n::text, 6, '0'),
        1000000.000000000000000000,
        NOW()
    FROM generate_series(1, ${ACCOUNTS}) AS n;

    CREATE TABLE IF NOT EXISTS stress_audit_log (
        id BIGSERIAL PRIMARY KEY,
        account_id VARCHAR(36) NOT NULL,
        amount NUMERIC(36,18) NOT NULL,
        recorded_at TIMESTAMPTZ DEFAULT NOW()
    );

    INSERT INTO stress_audit_log (account_id, amount, recorded_at)
    SELECT
        'stress-acc-' || LPAD(((n - 1) % ${ACCOUNTS} + 1)::text, 6, '0'),
        1.000000000000000000,
        NOW()
    FROM generate_series(1, ${ACCOUNTS} * ${TXN_PER_ACCOUNT}) AS n;

    CREATE INDEX IF NOT EXISTS idx_stress_audit_account ON stress_audit_log (account_id);
EOSQL

echo "perf-db seed.sh completed: seeded ${ACCOUNTS} accounts and $((${ACCOUNTS} * ${TXN_PER_ACCOUNT})) audit-log rows"