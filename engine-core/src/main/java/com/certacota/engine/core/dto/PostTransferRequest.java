package com.certacota.engine.core.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Map;

public record PostTransferRequest(
    @NotNull String accountId,
    @NotNull @NotBlank String toAccountId,
    @NotNull @Positive BigDecimal amount,
    @NotNull String idempotencyKey,
    @Nullable Map<String, Object> metadata,
    @Nullable @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal rakeRate,
    @Nullable String platformAccountId
) {
}
