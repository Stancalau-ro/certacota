package com.certacota.engine.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tag_committed_totals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagCommittedTotals {

    @Id
    @Column(name = "tag", nullable = false, updatable = false)
    private String tag;

    @Column(name = "total_debited", nullable = false, precision = ColumnConstants.AMOUNT_PRECISION, scale = ColumnConstants.AMOUNT_SCALE)
    private BigDecimal totalDebited;

    @Column(name = "total_credited_recipient", nullable = false, precision = ColumnConstants.AMOUNT_PRECISION, scale = ColumnConstants.AMOUNT_SCALE)
    private BigDecimal totalCreditedRecipient;

    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;

    public static TagCommittedTotals zero(String tag) {
        return TagCommittedTotals.builder()
            .tag(tag)
            .totalDebited(BigDecimal.ZERO)
            .totalCreditedRecipient(BigDecimal.ZERO)
            .lastActivityAt(OffsetDateTime.now())
            .build();
    }

    public void addDebit(BigDecimal amount) {
        this.totalDebited = this.totalDebited.add(amount);
        this.lastActivityAt = OffsetDateTime.now();
    }

    public void addCreditedRecipient(BigDecimal amount) {
        this.totalCreditedRecipient = this.totalCreditedRecipient.add(amount);
        this.lastActivityAt = OffsetDateTime.now();
    }
}
