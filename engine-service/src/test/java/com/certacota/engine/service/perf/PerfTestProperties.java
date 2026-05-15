package com.certacota.engine.service.perf;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "certacota.perf")
@Getter
@Setter
public class PerfTestProperties {

    private Concurrency concurrency = new Concurrency();
    private Mixed mixed = new Mixed();
    private Stress stress = new Stress();
    private EndByTag endByTag = new EndByTag();
    private Dr dr = new Dr();

    @Getter
    @Setter
    public static class Concurrency {
        private int threadCount = 20;
        private int rampUpSeconds = 2;
        private int durationSeconds = 30;
        private Sessions sessions = new Sessions();

        @Getter
        @Setter
        public static class Sessions {
            private int count = 10;
            private long minDurationMs = 1000L;
            private long maxDurationMs = 5000L;
        }
    }

    @Getter
    @Setter
    public static class Mixed {
        private int accounts = 50;
        private int durationSeconds = 30;
        private Sessions sessions = new Sessions();
        private IntervalRange creditIntervalMs = new IntervalRange(200L, 1000L);
        private AmountRange creditAmount = new AmountRange(new BigDecimal("1"), new BigDecimal("100"));
        private IntervalRange debitIntervalMs = new IntervalRange(200L, 1000L);
        private AmountRange debitAmount = new AmountRange(new BigDecimal("1"), new BigDecimal("100"));
        private HotAccounts hotAccounts = new HotAccounts();

        @Getter
        @Setter
        public static class Sessions {
            private int min = 1;
            private int max = 3;
        }
    }

    @Getter
    @Setter
    public static class IntervalRange {
        private long min;
        private long max;

        public IntervalRange() {
            this.min = 200L;
            this.max = 1000L;
        }

        public IntervalRange(long min, long max) {
            this.min = min;
            this.max = max;
        }
    }

    @Getter
    @Setter
    public static class AmountRange {
        private BigDecimal min;
        private BigDecimal max;

        public AmountRange() {
            this.min = new BigDecimal("1");
            this.max = new BigDecimal("100");
        }

        public AmountRange(BigDecimal min, BigDecimal max) {
            this.min = min;
            this.max = max;
        }
    }

    @Getter
    @Setter
    public static class HotAccounts {
        private int count = 5;
        private double contentionMultiplier = 3.0;
    }

    @Getter
    @Setter
    public static class Stress {
        private int accounts = 10000;
        private int transactionsPerAccount = 500000;
        private long maxQueryLatencyMs = 500L;
    }

    @Getter
    @Setter
    public static class EndByTag {
        private int tagCount = 5;
        private int sessionsPerTag = 10;
        private double overlapFactor = 0.3;
    }

    @Getter
    @Setter
    public static class Dr {
        private long postgresPauseDurationMs = 3000L;
        private long redisRestartWaitMs = 1000L;
    }
}
