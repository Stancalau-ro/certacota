package com.certacota.engine.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "streaming_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamingTransaction {

    public static final String ACTIVE = "ACTIVE";
    public static final String SETTLED = "SETTLED";
    public static final String ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false, updatable = false)
    private String streamId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "rate_per_second", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal ratePerSecond;

    @Column(name = "minimum_amount", precision = 38, scale = 18, updatable = false)
    private BigDecimal minimumAmount;

    @Column(name = "increment", precision = 38, scale = 18, updatable = false)
    private BigDecimal increment;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "stopped_at")
    private OffsetDateTime stoppedAt;

    @Column(name = "settled_amount", precision = 38, scale = 18)
    private BigDecimal settledAmount;

    @Column(name = "reason")
    private String reason;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Convert(converter = MetadataConverter.class)
    @Column(name = "metadata")
    private Map<String, Object> metadata;
}
