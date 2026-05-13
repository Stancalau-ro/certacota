package com.certacota.engine.core.dto;

import com.certacota.engine.core.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Map;

public record PostTransactionRequest(
    @NotNull String accountId,
    @NotNull TransactionType type,
    @NotNull @Positive BigDecimal amount,
    Map<String, Object> metadata,
    @NotNull String idempotencyKey,
    @NotBlank String toAccountId
) {
}
