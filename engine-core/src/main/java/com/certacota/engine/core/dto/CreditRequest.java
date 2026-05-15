package com.certacota.engine.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CreditRequest(
    @NotNull @Positive BigDecimal amount,
    @NotNull String idempotencyKey,
    @Nullable Map<String, Object> metadata,
    @Nullable @Size(max = 50) List<@NotBlank @Size(max = 255) @Pattern(regexp = "[^,]+", message = "Tag must not contain a comma") String> tags
) {
}
