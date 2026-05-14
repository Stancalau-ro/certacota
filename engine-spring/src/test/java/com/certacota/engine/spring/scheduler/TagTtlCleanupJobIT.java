package com.certacota.engine.spring.scheduler;

import com.certacota.engine.spring.config.TokenEngineProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TagTtlCleanupJobIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private JdbcTemplate jdbc;
    private TagTtlCleanupJob job;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tag_committed_totals (
                tag                       VARCHAR(255)   NOT NULL,
                total_debited             NUMERIC(38,18) NOT NULL DEFAULT 0,
                total_credited_recipient  NUMERIC(38,18) NOT NULL DEFAULT 0,
                last_activity_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
                CONSTRAINT pk_tag_committed_totals PRIMARY KEY (tag)
            )
            """);

        TokenEngineProperties properties = new TokenEngineProperties();
        job = new TagTtlCleanupJob(jdbc, properties);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS tag_committed_totals");
    }

    @Test
    void deletesRowOlderThanTtl() {
        jdbc.update("""
            INSERT INTO tag_committed_totals (tag, total_debited, total_credited_recipient, last_activity_at)
            VALUES ('old-tag', 0, 0, NOW() - INTERVAL '25 hours')
            """);

        int deleted = job.doCleanup();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_committed_totals WHERE tag = 'old-tag'", Integer.class))
            .isEqualTo(0);
    }

    @Test
    void retainsRowWithinTtl() {
        jdbc.update("""
            INSERT INTO tag_committed_totals (tag, total_debited, total_credited_recipient, last_activity_at)
            VALUES ('recent-tag', 0, 0, NOW() - INTERVAL '1 hour')
            """);

        int deleted = job.doCleanup();

        assertThat(deleted).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_committed_totals WHERE tag = 'recent-tag'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void deletesOnlyStaleRowsWhenBothPresent() {
        jdbc.update("""
            INSERT INTO tag_committed_totals (tag, total_debited, total_credited_recipient, last_activity_at)
            VALUES ('stale-tag', 0, 0, NOW() - INTERVAL '25 hours')
            """);
        jdbc.update("""
            INSERT INTO tag_committed_totals (tag, total_debited, total_credited_recipient, last_activity_at)
            VALUES ('fresh-tag', 0, 0, NOW() - INTERVAL '1 hour')
            """);

        int deleted = job.doCleanup();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_committed_totals WHERE tag = 'stale-tag'", Integer.class))
            .isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_committed_totals WHERE tag = 'fresh-tag'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void defaultTtlHoursIs24() {
        TokenEngineProperties properties = new TokenEngineProperties();
        assertThat(properties.getTags().getTtlHours()).isEqualTo(24);
    }

    @Test
    void defaultCleanupCronIs3AmDaily() {
        TokenEngineProperties properties = new TokenEngineProperties();
        assertThat(properties.getTags().getCleanupCron()).isEqualTo("0 0 3 * * *");
    }
}
