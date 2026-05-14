package com.certacota.engine.spring.scheduler;

import com.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static net.javacrumbs.shedlock.core.LockAssert.assertLocked;

@Component
@RequiredArgsConstructor
@Slf4j
public class TagTtlCleanupJob {

    private final JdbcTemplate jdbcTemplate;
    private final TokenEngineProperties properties;

    @Scheduled(cron = "${token-engine.tags.cleanup-cron:0 0 3 * * *}")
    @SchedulerLock(
        name = "tag_ttl_cleanup_job",
        lockAtMostFor = "PT1H",
        lockAtLeastFor = "PT1M"
    )
    @Transactional
    public void runCleanup() {
        assertLocked();
        // Delegate to inner method so tests can call doCleanup() directly,
        // bypassing the ShedLock assertLocked() guard that requires @Scheduled context.
        doCleanup();
    }

    // Package-private so TagTtlCleanupJobIT can invoke the DELETE logic directly.
    int doCleanup() {
        int deleted = jdbcTemplate.update(
            "DELETE FROM tag_committed_totals WHERE last_activity_at < NOW() - (? * INTERVAL '1 hour')",
            properties.getTags().getTtlHours());
        log.info("Tag TTL cleanup: deleted {} stale tag_committed_totals rows", deleted);
        return deleted;
    }
}
