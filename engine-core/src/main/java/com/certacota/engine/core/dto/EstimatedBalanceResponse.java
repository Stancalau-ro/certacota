package com.certacota.engine.core.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record EstimatedBalanceResponse(
    BigDecimal estimatedBalance,
    BigDecimal committedBalance,
    OffsetDateTime estimatedAt,
    Long estimatedDrainAt
) {
}
