package com.certacota.engine.core.domain;

import java.math.BigDecimal;
import java.util.Map;

public record StreamState(
    String streamId,
    String accountId,
    BigDecimal ratePerSecond,
    long startedAtEpochMillis,
    long startedAtNano,
    boolean startedAtNanoFromCurrentJvm,
    BigDecimal minimumAmount,
    BigDecimal increment
) {

    public static StreamState fromRedis(String streamId, Map<Object, Object> fields) {
        String accountId = (String) fields.get("accountId");
        BigDecimal ratePerSecond = new BigDecimal((String) fields.get("ratePerSecond"));
        long startedAtEpochMillis = Long.parseLong((String) fields.get("startedAtEpochMillis"));
        long startedAtNano = Long.parseLong((String) fields.get("startedAtNano"));

        String minAmountStr = (String) fields.get("minimumAmount");
        BigDecimal minimumAmount = (minAmountStr == null || minAmountStr.isEmpty()) ? null : new BigDecimal(minAmountStr);

        String incrementStr = (String) fields.get("increment");
        BigDecimal increment = (incrementStr == null || incrementStr.isEmpty()) ? null : new BigDecimal(incrementStr);

        return new StreamState(
            streamId,
            accountId,
            ratePerSecond,
            startedAtEpochMillis,
            startedAtNano,
            false,
            minimumAmount,
            increment
        );
    }

    public static StreamState fromDbRow(StreamingTransaction txn, long jvmStartNano) {
        long currentMillis = System.currentTimeMillis();
        long currentNano = System.nanoTime();
        long elapsedSinceStartMillis = currentMillis - txn.getStartedAt().toInstant().toEpochMilli();
        long startedAtNano = currentNano - (elapsedSinceStartMillis * 1_000_000L);

        return new StreamState(
            txn.getStreamId(),
            txn.getAccountId(),
            txn.getRatePerSecond(),
            txn.getStartedAt().toInstant().toEpochMilli(),
            startedAtNano,
            false,
            txn.getMinimumAmount(),
            txn.getIncrement()
        );
    }
}
