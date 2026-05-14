package com.certacota.engine.core.dto;

public record StopStreamRequest(
    boolean ignoreMinimum,
    String reason
) {
}
