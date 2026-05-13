package com.certacota.engine.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "accounts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    @Column(name = "balance", nullable = false, precision = 38, scale = 18)
    private BigDecimal balance;

    @Column(name = "balance_floor", precision = 38, scale = 18)
    private BigDecimal balanceFloor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public void close() {
        this.status = AccountStatus.CLOSED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
        this.updatedAt = OffsetDateTime.now();
    }

    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
        this.updatedAt = OffsetDateTime.now();
    }
}
