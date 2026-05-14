package com.certacota.engine.core.dto;

import java.math.BigDecimal;
import java.util.List;

public record EndByTagResponse(
    int settledCount,
    int skippedCount,
    List<SettledStream> settledStreams
) {
    public record SettledStream(String streamId, BigDecimal settledAmount) { }
}
