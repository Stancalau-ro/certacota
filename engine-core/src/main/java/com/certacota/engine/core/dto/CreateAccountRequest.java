package com.certacota.engine.core.dto;

import java.math.BigDecimal;
import java.util.Map;

public record CreateAccountRequest(
    String id,
    BigDecimal initialBalance,
    BigDecimal balanceFloor,
    Map<String, Object> metadata,
    String idempotencyKey
) {
}
