package com.certacota.engine.core.dto;

import com.certacota.engine.core.domain.StreamingTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StartStreamResponse(
    String streamId,
    String accountId,
    BigDecimal ratePerSecond,
    OffsetDateTime startedAt,
    BigDecimal minimumAmount,
    BigDecimal increment
) {
    public static StartStreamResponse from(StreamingTransaction txn) {
        return new StartStreamResponse(
            txn.getStreamId(),
            txn.getAccountId(),
            txn.getRatePerSecond(),
            txn.getStartedAt(),
            txn.getMinimumAmount(),
            txn.getIncrement()
        );
    }
}
