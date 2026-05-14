package com.certacota.engine.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record StartStreamRequest(
    @NotBlank String streamId,
    @NotBlank String accountId,
    @NotNull @Positive BigDecimal ratePerSecond,
    @NotBlank String idempotencyKey,
    @Positive BigDecimal minimumAmount,
    @Positive BigDecimal increment
) {
}
