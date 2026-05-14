package com.certacota.engine.spring.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "token-engine")
@Getter
@Setter
public class TokenEngineProperties {

    private BigDecimal balanceFloor = BigDecimal.ZERO;

    private StreamingProperties streaming = new StreamingProperties();
    private AuditProperties audit = new AuditProperties();
    private RedisProperties redis = new RedisProperties();

    @Getter
    @Setter
    public static class StreamingProperties {
        private long fallbackSweepSeconds = 300;
    }

    @Getter
    @Setter
    public static class AuditProperties {
        private int retentionDays = 90;
        private String cron = "0 0 2 * * *";
        private String lockAtMostHours = "PT2H";
        private String lockAtLeastMinutes = "PT1M";
        private int idempotencyTtlHours = 48;
    }

    @Getter
    @Setter
    public static class RedisProperties {
        private String sentinelMaster;
        private String sentinelNodes;
    }

    private TagProperties tags = new TagProperties();

    @Getter
    @Setter
    public static class TagProperties {
        private int ttlHours = 24;
        private String cleanupCron = "0 0 3 * * *";
    }
}
