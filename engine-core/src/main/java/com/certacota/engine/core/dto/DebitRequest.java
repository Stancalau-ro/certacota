package com.certacota.engine.core.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Map;

public record DebitRequest(
    @NotNull @Positive BigDecimal amount,
    @NotNull String idempotencyKey,
    Map<String, Object> metadata
) {
}
