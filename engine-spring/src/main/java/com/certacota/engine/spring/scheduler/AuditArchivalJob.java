package com.certacota.engine.spring.scheduler;

import com.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static net.javacrumbs.shedlock.core.LockAssert.assertLocked;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditArchivalJob {

    private final JdbcTemplate jdbcTemplate;
    private final TokenEngineProperties properties;

    @Scheduled(cron = "${token-engine.audit.cron:0 0 2 * * *}")
    @SchedulerLock(
        name = "audit_archival_job",
        lockAtMostFor = "${token-engine.audit.lock-at-most-hours:PT2H}",
        lockAtLeastFor = "${token-engine.audit.lock-at-least-minutes:PT1M}"
    )
    public void runArchival() {
        assertLocked();

        int retentionDays = properties.getAudit().getRetentionDays();

        // Step 1: Archive rows before deletion — invariant: no direct DELETE without prior INSERT INTO audit_archive
        int archived = jdbcTemplate.update(
            "INSERT INTO audit_archive.balance_audit_log SELECT * FROM balance_audit_log WHERE recorded_at < NOW() - (? * INTERVAL '1 day')",
            retentionDays);
        log.info("Audit archival: copied {} rows to audit_archive.balance_audit_log", archived);

        // Step 2: Delete archived rows from the live table
        int deleted = jdbcTemplate.update(
            "DELETE FROM balance_audit_log WHERE recorded_at < NOW() - (? * INTERVAL '1 day')",
            retentionDays);
        log.info("Audit archival: deleted {} rows from balance_audit_log", deleted);

        // Step 3: Sweep expired idempotency keys by TTL
        int idempotencyDeleted = jdbcTemplate.update(
            "DELETE FROM idempotency_keys WHERE created_at < NOW() - (? * INTERVAL '1 hour')",
            properties.getAudit().getIdempotencyTtlHours());
        log.info("Audit archival: deleted {} expired idempotency keys", idempotencyDeleted);
    }
}
