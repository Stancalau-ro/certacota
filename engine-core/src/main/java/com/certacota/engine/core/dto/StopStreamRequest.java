package com.certacota.engine.core.dto;

import org.jspecify.annotations.Nullable;

public record StopStreamRequest(
    boolean ignoreMinimum,
    @Nullable String reason
) {
}
