package com.certacota.engine.spring;

import com.certacota.engine.core.domain.StreamSettlementCalculator;
import com.certacota.engine.core.domain.StreamState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ArithmeticTest {

    private StreamState buildState(BigDecimal ratePerSecond, BigDecimal minimumAmount, BigDecimal increment) {
        return new StreamState(
            "test-stream",
            "test-account",
            ratePerSecond,
            System.currentTimeMillis(),
            System.nanoTime(),
            true,
            minimumAmount,
            increment
        );
    }

    @Test
    void plainElapsedSettlement() {
        StreamState state = buildState(new BigDecimal("2.5"), null, null);
        BigDecimal result = StreamSettlementCalculator.computeSettledAmount(state, false, new BigDecimal("3.0"));
        assertThat(result.compareTo(new BigDecimal("7.5"))).isEqualTo(0);
    }

    @Test
    void incrementBillingRoundsDown() {
        StreamState state = buildState(new BigDecimal("2.0"), null, new BigDecimal("5.0"));
        BigDecimal result = StreamSettlementCalculator.computeSettledAmount(state, false, new BigDecimal("3.7"));
        assertThat(result.compareTo(new BigDecimal("5.0"))).isEqualTo(0);
    }

    @Test
    void minimumAmountEnforced() {
        StreamState state = buildState(new BigDecimal("0.1"), new BigDecimal("5.0"), null);
        BigDecimal result = StreamSettlementCalculator.computeSettledAmount(state, false, new BigDecimal("1.0"));
        assertThat(result.compareTo(new BigDecimal("5.0"))).isEqualTo(0);
    }

    @Test
    void minimumAmountWaived() {
        StreamState state = buildState(new BigDecimal("0.1"), new BigDecimal("5.0"), null);
        BigDecimal result = StreamSettlementCalculator.computeSettledAmount(state, true, new BigDecimal("1.0"));
        assertThat(result.compareTo(new BigDecimal("0.1"))).isEqualTo(0);
    }

    @Test
    void settlementClamped() {
        BigDecimal result = StreamSettlementCalculator.clampToAvailableBalance(
            new BigDecimal("100.0"),
            new BigDecimal("30.0"),
            BigDecimal.ZERO
        );
        assertThat(result.compareTo(new BigDecimal("30.0"))).isEqualTo(0);
    }
}
