package com.certacota.engine.spring.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class StreamingTransactionRakeConstraintIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS streaming_transactions (
                id              BIGSERIAL        NOT NULL,
                stream_id       VARCHAR(255)     NOT NULL,
                account_id      VARCHAR(255)     NOT NULL,
                status          VARCHAR(20)      NOT NULL DEFAULT 'ACTIVE',
                rate_per_second NUMERIC(38,18)   NOT NULL,
                minimum_amount  NUMERIC(38,18),
                increment       NUMERIC(38,18),
                started_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
                stopped_at      TIMESTAMPTZ,
                settled_amount  NUMERIC(38,18),
                reason          VARCHAR(255),
                idempotency_key VARCHAR(255),
                to_account_id   VARCHAR(255),
                rake_rate       NUMERIC(38,18),
                platform_account_id VARCHAR(255),
                to_account_amount   NUMERIC(38,18),
                rake_amount         NUMERIC(38,18),
                CONSTRAINT pk_streaming_transactions PRIMARY KEY (id)
            )
            """);

        jdbc.execute("""
            ALTER TABLE streaming_transactions
                ADD CONSTRAINT chk_str_rake_balanced
                    CHECK (settled_amount IS NULL
                        OR to_account_amount IS NULL
                        OR rake_amount IS NULL
                        OR settled_amount = to_account_amount + rake_amount)
            """);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS streaming_transactions");
    }

    @Test
    void balancedRakeRowInsertSucceeds() {
        assertThatCode(() -> jdbc.execute("""
            INSERT INTO streaming_transactions
                (stream_id, account_id, status, rate_per_second, started_at, settled_amount, to_account_amount, rake_amount)
            VALUES ('s1', 'a1', 'SETTLED', 1.0, now(), 100, 90, 10)
            """)).doesNotThrowAnyException();
    }

    @Test
    void unbalancedRakeRowViolatesConstraint() {
        assertThatThrownBy(() -> jdbc.execute("""
            INSERT INTO streaming_transactions
                (stream_id, account_id, status, rate_per_second, started_at, settled_amount, to_account_amount, rake_amount)
            VALUES ('s2', 'a2', 'SETTLED', 1.0, now(), 100, 90, 11)
            """)).hasMessageContaining("chk_str_rake_balanced");
    }

    @Test
    void nullSettledAmountAllowed() {
        assertThatCode(() -> jdbc.execute("""
            INSERT INTO streaming_transactions
                (stream_id, account_id, status, rate_per_second, started_at, settled_amount, to_account_amount, rake_amount)
            VALUES ('s3', 'a3', 'ACTIVE', 1.0, now(), NULL, NULL, NULL)
            """)).doesNotThrowAnyException();
    }

    @Test
    void nullToAccountAmountAllowed() {
        assertThatCode(() -> jdbc.execute("""
            INSERT INTO streaming_transactions
                (stream_id, account_id, status, rate_per_second, started_at, settled_amount, to_account_amount, rake_amount)
            VALUES ('s4', 'a4', 'SETTLED', 1.0, now(), 100, NULL, NULL)
            """)).doesNotThrowAnyException();
    }
}
