package com.certacota.engine.core.dto;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Map;

public record CreateAccountRequest(
    String id,
    @Nullable BigDecimal initialBalance,
    @Nullable BigDecimal balanceFloor,
    @Nullable Map<String, Object> metadata,
    String idempotencyKey
) {
}
