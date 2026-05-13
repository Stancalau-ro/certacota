package com.certacota.engine.core.dto;

import com.certacota.engine.core.domain.TransactionType;

import java.math.BigDecimal;
import java.util.Map;

public record PostTransactionRequest(
    String accountId,
    TransactionType type,
    BigDecimal amount,
    Map<String, Object> metadata,
    String idempotencyKey,
    String toAccountId
) {
}
