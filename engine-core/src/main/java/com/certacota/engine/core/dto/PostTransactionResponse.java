package com.certacota.engine.core.dto;

import com.certacota.engine.core.domain.DiscreteTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record PostTransactionResponse(
    Long transactionId,
    String accountId,
    String type,
    BigDecimal amount,
    BigDecimal balanceAfter,
    Map<String, Object> metadata,
    OffsetDateTime postedAt
) {
    public static PostTransactionResponse from(DiscreteTransaction txn, BigDecimal balanceAfter) {
        return new PostTransactionResponse(
            txn.getId(),
            txn.getAccountId(),
            txn.getType().name(),
            txn.getAmount(),
            balanceAfter,
            txn.getMetadata(),
            txn.getPostedAt()
        );
    }
}
