package com.certacota.engine.core.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

public record StartStreamRequest(
    @NotBlank String streamId,
    @NotBlank String accountId,
    @NotNull @Positive BigDecimal ratePerSecond,
    @NotBlank String idempotencyKey,
    @Nullable @Positive BigDecimal minimumAmount,
    @Nullable @Positive BigDecimal increment,
    @Nullable @Size(max = 50) List<@NotBlank @Size(max = 255) @Pattern(regexp = "[^,]+", message = "Tag must not contain a comma") String> tags,
    @Nullable String toAccountId,
    @Nullable @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal rakeRate,
    @Nullable String platformAccountId
) {
}
