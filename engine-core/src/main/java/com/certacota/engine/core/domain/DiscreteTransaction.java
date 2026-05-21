package com.certacota.engine.core.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

    @Column(name = "amount", nullable = false, updatable = false, precision = ColumnConstants.AMOUNT_PRECISION, scale = ColumnConstants.AMOUNT_SCALE)
    private BigDecimal amount;

    @Convert(converter = MetadataConverter.class)
    @Column(name = "metadata")
    @Nullable private Map<String, Object> metadata;

    @Column(name = "idempotency_key", updatable = false)
    @Nullable private String idempotencyKey;

    @Column(name = "posted_at", updatable = false)
    @Nullable private OffsetDateTime postedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "discrete_transaction_tags",
        joinColumns = @JoinColumn(name = "transaction_id")
    )
    @Column(name = "tag")
    private List<String> tags;
}
