package com.certacota.engine.core.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StopStreamResponse(
    BigDecimal settledAmount,
    OffsetDateTime stoppedAt,
    String reason
) {
}
