package com.certacota.engine.core.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TagAggregateResponse(
    CommittedSide committed,
    InFlightSide inFlight,
    OffsetDateTime estimatedAt
) {
    public record CommittedSide(
        BigDecimal totalDebited,
        BigDecimal totalCreditedRecipient,
        BigDecimal totalRaked
    ) { }

    public record InFlightSide(
        BigDecimal inFlightDebit,
        BigDecimal inFlightCreditedRecipient,
        BigDecimal inFlightRaked
    ) { }
}
