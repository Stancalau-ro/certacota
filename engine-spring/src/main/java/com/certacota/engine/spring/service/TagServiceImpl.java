package com.certacota.engine.spring.service;

import com.certacota.engine.core.domain.StreamSettlementCalculator;
import com.certacota.engine.core.domain.StreamState;
import com.certacota.engine.core.domain.TagCommittedTotals;
import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;
import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TagServiceImpl implements TagService {

    private final TagCommittedTotalsRepository tagCommittedTotalsRepository;
    private final StreamRegistry streamRegistry;

    @Override
    @Transactional(readOnly = true)
    public TagAggregateResponse aggregate(String tag) {
        log.info("Tag aggregate request for tag={}", tag);

        TagCommittedTotals committedRow = tagCommittedTotalsRepository.findById(tag).orElse(null);

        BigDecimal totalDebited = BigDecimal.ZERO;
        BigDecimal totalCreditedRecipient = BigDecimal.ZERO;
        if (committedRow != null) {
            totalDebited = committedRow.getTotalDebited();
            totalCreditedRecipient = committedRow.getTotalCreditedRecipient();
        }
        BigDecimal totalRaked = totalDebited.subtract(totalCreditedRecipient);

        List<StreamState> activeTagStreams = streamRegistry.getStreamsByTag(tag);
        BigDecimal inFlightDebit = BigDecimal.ZERO;
        BigDecimal inFlightCreditedRecipient = BigDecimal.ZERO;

        for (StreamState s : activeTagStreams) {
            BigDecimal projection = StreamSettlementCalculator.computeProjection(s);
            inFlightDebit = inFlightDebit.add(projection);
            BigDecimal rakeRate = s.rakeRate() != null ? s.rakeRate() : BigDecimal.ZERO;
            BigDecimal inFlightRakeForStream = projection.multiply(rakeRate).setScale(18, RoundingMode.DOWN);
            inFlightCreditedRecipient = inFlightCreditedRecipient.add(projection.subtract(inFlightRakeForStream));
        }
        BigDecimal inFlightRaked = inFlightDebit.subtract(inFlightCreditedRecipient);

        return new TagAggregateResponse(
            new TagAggregateResponse.CommittedSide(totalDebited, totalCreditedRecipient, totalRaked),
            new TagAggregateResponse.InFlightSide(inFlightDebit, inFlightCreditedRecipient, inFlightRaked),
            OffsetDateTime.now()
        );
    }

    @Override
    @Transactional
    public EndByTagResponse endByTag(String tag, String idempotencyKey, String reason) {
        log.warn("endByTag for tag={} called but not yet implemented — plan 04 delivers this", tag);
        throw new UnsupportedOperationException("endByTag implemented in plan 04");
    }
}
