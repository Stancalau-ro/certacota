package com.certacota.engine.core.domain;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record StreamState(
    String streamId,
    String accountId,
    BigDecimal ratePerSecond,
    long startedAtEpochMillis,
    long startedAtNano,
    boolean startedAtNanoFromCurrentJvm,
    @Nullable BigDecimal minimumAmount,
    @Nullable BigDecimal increment,
    List<String> tags,
    @Nullable String toAccountId,
    @Nullable BigDecimal rakeRate,
    @Nullable String platformAccountId
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

        String tagsStr = (String) fields.getOrDefault("tags", "");
        List<String> tags = (tagsStr == null || tagsStr.isEmpty())
            ? Collections.emptyList()
            : List.of(tagsStr.split(","));

        String toAccountId = (String) fields.get("toAccountId");
        if (toAccountId != null && toAccountId.isEmpty()) {
            toAccountId = null;
        }

        String rakeRateStr = (String) fields.get("rakeRate");
        BigDecimal rakeRate = (rakeRateStr == null || rakeRateStr.isEmpty()) ? null : new BigDecimal(rakeRateStr);

        String platformAccountId = (String) fields.get("platformAccountId");
        if (platformAccountId != null && platformAccountId.isEmpty()) {
            platformAccountId = null;
        }

        return new StreamState(
            streamId,
            accountId,
            ratePerSecond,
            startedAtEpochMillis,
            startedAtNano,
            false,
            minimumAmount,
            increment,
            tags,
            toAccountId,
            rakeRate,
            platformAccountId
        );
    }

    public static StreamState fromDbRow(StreamingTransaction txn) {
        return new StreamState(
            txn.getStreamId(),
            txn.getAccountId(),
            txn.getRatePerSecond(),
            txn.getStartedAt().toInstant().toEpochMilli(),
            0L,
            false,
            txn.getMinimumAmount(),
            txn.getIncrement(),
            txn.getTags() != null ? txn.getTags() : Collections.emptyList(),
            txn.getToAccountId(),
            txn.getRakeRate(),
            txn.getPlatformAccountId()
        );
    }
}
