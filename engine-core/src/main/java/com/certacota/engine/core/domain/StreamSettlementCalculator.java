package com.certacota.engine.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class StreamSettlementCalculator {

    private StreamSettlementCalculator() {
    }

    public static BigDecimal computeSettledAmount(StreamState state, boolean ignoreMinimum, BigDecimal elapsedSeconds) {
        BigDecimal projected = state.ratePerSecond()
            .multiply(elapsedSeconds)
            .setScale(18, RoundingMode.DOWN);

        if (state.increment() != null && state.increment().compareTo(BigDecimal.ZERO) > 0) {
            projected = projected
                .divide(state.increment(), 0, RoundingMode.FLOOR)
                .multiply(state.increment())
                .setScale(18, RoundingMode.DOWN);
        }

        if (!ignoreMinimum
                && state.minimumAmount() != null
                && projected.compareTo(state.minimumAmount()) < 0) {
            projected = state.minimumAmount();
        }

        return projected;
    }

    public static BigDecimal computeProjection(StreamState state) {
        long elapsedMillis = System.currentTimeMillis() - state.startedAtEpochMillis();
        BigDecimal elapsedSeconds = BigDecimal.valueOf(elapsedMillis)
            .divide(BigDecimal.valueOf(1000L), 18, RoundingMode.DOWN);
        return state.ratePerSecond()
            .multiply(elapsedSeconds)
            .setScale(18, RoundingMode.DOWN);
    }

    public static BigDecimal clampToAvailableBalance(BigDecimal projected, BigDecimal accountBalance, BigDecimal effectiveFloor) {
        BigDecimal available = accountBalance.subtract(effectiveFloor);
        return projected.min(available);
    }
}
