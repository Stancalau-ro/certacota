package com.certacota.engine.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "discrete_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscreteTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "type", nullable = false, updatable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransactionType type;

    @Column(name = "amount", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "posted_at", updatable = false)
    private OffsetDateTime postedAt;
}
