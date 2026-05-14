package com.certacota.engine.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TagCommittedTotalsTest {

    @Test
    void zeroFactory_setsAllFieldsCorrectly() {
        OffsetDateTime before = OffsetDateTime.now();
        TagCommittedTotals totals = TagCommittedTotals.zero("foo");
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(totals.getTag()).isEqualTo("foo");
        assertThat(totals.getTotalDebited()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.getTotalCreditedRecipient()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.getLastActivityAt()).isAfterOrEqualTo(before);
        assertThat(totals.getLastActivityAt()).isBeforeOrEqualTo(after);
    }

    @Test
    void addDebit_incrementsTotalDebitedAndUpdatesLastActivityAt() throws InterruptedException {
        TagCommittedTotals totals = TagCommittedTotals.zero("foo");
        OffsetDateTime before = OffsetDateTime.now();

        totals.addDebit(BigDecimal.TEN);

        assertThat(totals.getTotalDebited()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(totals.getTotalCreditedRecipient()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.getLastActivityAt()).isAfterOrEqualTo(before);
    }

    @Test
    void addCreditedRecipient_incrementsTotalCreditedRecipientAndUpdatesLastActivityAt() {
        TagCommittedTotals totals = TagCommittedTotals.zero("bar");
        OffsetDateTime before = OffsetDateTime.now();

        totals.addCreditedRecipient(BigDecimal.ONE);

        assertThat(totals.getTotalCreditedRecipient()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(totals.getTotalDebited()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.getLastActivityAt()).isAfterOrEqualTo(before);
    }

    @Test
    void addDebit_multipleCallsAccumulate() {
        TagCommittedTotals totals = TagCommittedTotals.zero("baz");

        totals.addDebit(new BigDecimal("5.0"));
        totals.addDebit(new BigDecimal("3.0"));

        assertThat(totals.getTotalDebited()).isEqualByComparingTo(new BigDecimal("8.0"));
    }

    @Test
    void noTotalRakedField() throws NoSuchFieldException {
        try {
            TagCommittedTotals.class.getDeclaredField("totalRaked");
            throw new AssertionError("totalRaked field must NOT exist — it is derived at query time");
        } catch (NoSuchFieldException e) {
            // expected — totalRaked is never stored
        }
    }
}
