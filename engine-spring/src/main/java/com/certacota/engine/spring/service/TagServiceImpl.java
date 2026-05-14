package com.certacota.engine.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.IdempotencyKey;
import com.certacota.engine.core.domain.StreamSettlementCalculator;
import com.certacota.engine.core.domain.StreamState;
import com.certacota.engine.core.domain.StreamingTransaction;
import com.certacota.engine.core.domain.TagCommittedTotals;
import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.StopStreamRequest;
import com.certacota.engine.core.dto.StopStreamResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.StreamingService;
import com.certacota.engine.core.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TagServiceImpl implements TagService {

    private final TagCommittedTotalsRepository tagCommittedTotalsRepository;
    private final StreamRegistry streamRegistry;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final StreamingService streamingService;
    private final ObjectMapper objectMapper;
    private final StreamingTransactionRepository streamingTransactionRepository;

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
        log.info("End-by-tag for tag={}, idempotencyKey={}", tag, idempotencyKey);

        String effectiveReason = reason != null ? reason : "end_by_tag";

        // Pre-write idempotency check — return cached response if already processed (D-18)
        var existing = idempotencyKeyRepository.findByIdempotencyKeyAndOperation(idempotencyKey, "BULK_END_BY_TAG");
        if (existing.isPresent()) {
            log.info("Returning cached idempotent response for end-by-tag key: {}", idempotencyKey);
            return deserialize(existing.get().getResponseBody(), EndByTagResponse.class);
        }

        // Pending-first write: a concurrent identical request hitting the UNIQUE constraint will fail
        // fast rather than executing the bulk settlement twice (mirrors startStream lines 81-87).
        IdempotencyKey pendingKey = idempotencyKeyRepository.save(IdempotencyKey.builder()
            .idempotencyKey(idempotencyKey)
            .operation("BULK_END_BY_TAG")
            .responseBody("pending")
            .createdAt(OffsetDateTime.now())
            .build());

        List<StreamState> candidates = streamRegistry.getStreamsByTag(tag);

        List<EndByTagResponse.SettledStream> settled = new ArrayList<>();
        int skippedCount = 0;

        for (StreamState s : candidates) {
            StreamingTransaction txn = streamingTransactionRepository.findByStreamId(s.streamId()).orElse(null);
            if (txn == null) {
                skippedCount++;
                log.warn("Stream {} in tag set but missing in DB — skipping", s.streamId());
                continue;
            }
            if (StreamingTransaction.SETTLED.equals(txn.getStatus()) || StreamingTransaction.ERROR.equals(txn.getStatus())) {
                // Already-settled streams silently skipped per D-20; counted in skippedCount
                skippedCount++;
                continue;
            }
            // Propagation REQUIRED: stopStream joins this outer transaction so all stream
            // settlements share one DB commit. If any inner stopStream throws, the WHOLE
            // transaction rolls back including all prior settlements and the pending idempotency
            // key — allowing a clean retry. This is the desired all-or-nothing semantics for
            // TAG-02 (Pitfall 1 mitigation). Do NOT catch exceptions here.
            StopStreamResponse resp = streamingService.stopStream(s.streamId(), new StopStreamRequest(false, effectiveReason));
            settled.add(new EndByTagResponse.SettledStream(s.streamId(), resp.settledAmount()));
        }

        EndByTagResponse response = new EndByTagResponse(settled.size(), skippedCount, settled);

        try {
            pendingKey.updateResponseBody(objectMapper.writeValueAsString(response));
            idempotencyKeyRepository.save(pendingKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update bulk-end-by-tag idempotency key response body", e);
        }

        return response;
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached response", e);
        }
    }
}
