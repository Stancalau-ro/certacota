package com.certacota.engine.core.domain;

import jakarta.persistence.Column;
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

@Entity
@Table(name = "balance_audit_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "operation", nullable = false, updatable = false, length = 50)
    private String operation;

    @Column(name = "amount", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal balanceAfter;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "recorded_at", updatable = false)
    private OffsetDateTime recordedAt;
}
