package com.certacota.engine.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.Account;
import com.certacota.engine.core.domain.AccountStatus;
import com.certacota.engine.core.domain.BalanceAuditLog;
import com.certacota.engine.core.domain.IdempotencyKey;
import com.certacota.engine.core.domain.StreamSettlementCalculator;
import com.certacota.engine.core.domain.StreamState;
import com.certacota.engine.core.domain.StreamingTransaction;
import com.certacota.engine.core.dto.EstimatedBalanceResponse;
import com.certacota.engine.core.dto.StartStreamRequest;
import com.certacota.engine.core.dto.StartStreamResponse;
import com.certacota.engine.core.dto.StopStreamRequest;
import com.certacota.engine.core.dto.StopStreamResponse;
import com.certacota.engine.core.exception.AccountClosedException;
import com.certacota.engine.core.exception.AccountNotFoundException;
import com.certacota.engine.core.exception.StreamAlreadyActiveException;
import com.certacota.engine.core.exception.StreamNotFoundException;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.StreamingService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.scheduler.AutoTerminationScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class StreamingServiceImpl implements StreamingService {

    private final AccountRepository accountRepository;
    private final StreamingTransactionRepository streamingTransactionRepository;
    private final BalanceAuditLogRepository auditLogRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final StreamRegistry streamRegistry;
    private final TokenEngineProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Lazy
    @Autowired
    private AutoTerminationScheduler autoTerminationScheduler;

    @Override
    public StartStreamResponse startStream(StartStreamRequest request) {
        log.info("Starting stream {} for account {}", request.streamId(), request.accountId());

        Account account = accountRepository.findWithLock(request.accountId())
            .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

        var existing = idempotencyKeyRepository
            .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "STREAM_START");
        if (existing.isPresent()) {
            log.info("Returning cached idempotent response for stream start key: {}", request.idempotencyKey());
            return deserialize(existing.get().getResponseBody(), StartStreamResponse.class);
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            log.warn("Attempt to start stream on closed account: {}", request.accountId());
            throw new AccountClosedException(request.accountId());
        }

        boolean alreadyActive = streamRegistry.get(request.streamId()).isPresent()
            || streamingTransactionRepository.findByStreamId(request.streamId())
                .map(t -> StreamingTransaction.ACTIVE.equals(t.getStatus()))
                .orElse(false);
        if (alreadyActive) {
            throw new StreamAlreadyActiveException(request.streamId());
        }

        BigDecimal effectiveFloor = account.getBalanceFloor() != null
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();

        List<StreamState> activeStreams = streamRegistry.getActiveStreams(request.accountId());
        BigDecimal totalProjected = activeStreams.stream()
            .map(StreamSettlementCalculator::computeProjection)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimatedBalance = account.getBalance().subtract(totalProjected);

        BigDecimal outstandingMinimums = activeStreams.stream()
            .filter(s -> s.minimumAmount() != null)
            .map(s -> {
                BigDecimal proj = StreamSettlementCalculator.computeProjection(s);
                BigDecimal remaining = s.minimumAmount().subtract(proj);
                return remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : BigDecimal.ZERO;
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (outstandingMinimums.compareTo(BigDecimal.ZERO) > 0
                && estimatedBalance.compareTo(outstandingMinimums) < 0) {
            throw new com.certacota.engine.core.exception.BalanceFloorViolationException(
                "Insufficient estimated balance to cover minimum amount obligations");
        }

        StreamingTransaction txn = streamingTransactionRepository.save(StreamingTransaction.builder()
            .streamId(request.streamId())
            .accountId(request.accountId())
            .status(StreamingTransaction.ACTIVE)
            .ratePerSecond(request.ratePerSecond())
            .minimumAmount(request.minimumAmount())
            .increment(request.increment())
            .startedAt(OffsetDateTime.now())
            .idempotencyKey(request.idempotencyKey())
            .build());

        auditLogRepository.save(BalanceAuditLog.builder()
            .accountId(account.getId())
            .operation("STREAMING_START")
            .amount(BigDecimal.ZERO)
            .balanceBefore(account.getBalance())
            .balanceAfter(account.getBalance())
            .idempotencyKey(request.idempotencyKey())
            .recordedAt(OffsetDateTime.now())
            .build());

        StreamState state = new StreamState(
            request.streamId(),
            request.accountId(),
            request.ratePerSecond(),
            System.currentTimeMillis(),
            System.nanoTime(),
            true,
            request.minimumAmount(),
            request.increment()
        );
        streamRegistry.register(state);

        BigDecimal effectiveFloorForScheduler = account.getBalanceFloor() != null
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();
        long exhaustionDelayMillis = AutoTerminationScheduler.calculateExhaustionDelayMillis(
            estimatedBalance, effectiveFloorForScheduler, request.ratePerSecond());
        autoTerminationScheduler.enqueue(request.streamId(), exhaustionDelayMillis);

        StartStreamResponse response = StartStreamResponse.from(txn);
        storeIdempotencyKey(request.idempotencyKey(), "STREAM_START", response);
        return response;
    }

    @Override
    public StopStreamResponse stopStream(String streamId, StopStreamRequest request) {
        log.info("Stopping stream {}", streamId);

        StreamState state = streamRegistry.get(streamId)
            .orElseThrow(() -> new StreamNotFoundException(streamId));

        Account account = accountRepository.findWithLock(state.accountId())
            .orElseThrow(() -> new AccountNotFoundException(state.accountId()));

        BigDecimal elapsedSeconds = computeElapsedSeconds(state);
        BigDecimal settledAmount = StreamSettlementCalculator.computeSettledAmount(state, request.ignoreMinimum(), elapsedSeconds);

        BigDecimal effectiveFloor = account.getBalanceFloor() != null
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();
        BigDecimal clampedAmount = StreamSettlementCalculator.clampToAvailableBalance(
            settledAmount, account.getBalance(), effectiveFloor);

        BigDecimal balanceBefore = account.getBalance();
        account.debit(clampedAmount);
        accountRepository.save(account);

        String reason = request.reason() != null ? request.reason() : "stop endpoint call";
        OffsetDateTime stoppedAt = OffsetDateTime.now();

        streamingTransactionRepository.findByStreamId(streamId).ifPresent(txn -> {
            StreamingTransaction settled = StreamingTransaction.builder()
                .id(txn.getId())
                .streamId(txn.getStreamId())
                .accountId(txn.getAccountId())
                .status(StreamingTransaction.SETTLED)
                .ratePerSecond(txn.getRatePerSecond())
                .minimumAmount(txn.getMinimumAmount())
                .increment(txn.getIncrement())
                .startedAt(txn.getStartedAt())
                .stoppedAt(stoppedAt)
                .settledAmount(clampedAmount)
                .reason(reason)
                .idempotencyKey(txn.getIdempotencyKey())
                .build();
            streamingTransactionRepository.save(settled);

            auditLogRepository.save(BalanceAuditLog.builder()
                .accountId(account.getId())
                .operation("STREAMING_SETTLE")
                .amount(clampedAmount)
                .balanceBefore(balanceBefore)
                .balanceAfter(account.getBalance())
                .recordedAt(stoppedAt)
                .build());
        });

        autoTerminationScheduler.cancel(streamId);
        streamRegistry.remove(streamId, state.accountId());

        return new StopStreamResponse(clampedAmount, stoppedAt, reason);
    }

    @Override
    public void autoTerminate(String streamId) {
        try {
            stopStream(streamId, new StopStreamRequest(true, "balance_exhaustion"));
        } catch (StreamNotFoundException e) {
            log.warn("Auto-terminate called on non-existent or already-settled stream: {}", streamId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EstimatedBalanceResponse estimateBalance(String accountId) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

        List<StreamState> activeStreams = streamRegistry.getActiveStreams(accountId);
        OffsetDateTime estimatedAt = OffsetDateTime.now();

        BigDecimal totalProjected = activeStreams.stream()
            .map(StreamSettlementCalculator::computeProjection)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal estimatedBalance = account.getBalance().subtract(totalProjected);

        BigDecimal totalRate = activeStreams.stream()
            .map(StreamState::ratePerSecond)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal effectiveFloor = account.getBalanceFloor() != null
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();

        Long estimatedDrainAt = null;
        if (totalRate.compareTo(BigDecimal.ZERO) > 0 && estimatedBalance.compareTo(effectiveFloor) > 0) {
            BigDecimal secondsToFloor = estimatedBalance.subtract(effectiveFloor)
                .divide(totalRate, 3, RoundingMode.DOWN);
            estimatedDrainAt = estimatedAt.toInstant().toEpochMilli()
                + secondsToFloor.multiply(BigDecimal.valueOf(1000)).longValue();
        }

        return new EstimatedBalanceResponse(estimatedBalance, account.getBalance(), estimatedAt, estimatedDrainAt);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Starting up: reconciling ACTIVE streaming transactions from Postgres into Redis");
        List<StreamingTransaction> activeTransactions = streamingTransactionRepository.findByStatus(StreamingTransaction.ACTIVE);
        int reconciled = 0;
        int autoTerminated = 0;

        for (StreamingTransaction txn : activeTransactions) {
            try {
                Account account = accountRepository.findById(txn.getAccountId()).orElse(null);
                if (account == null) {
                    log.warn("Startup reconciliation: account {} not found for stream {}", txn.getAccountId(), txn.getStreamId());
                    continue;
                }

                BigDecimal effectiveFloor = account.getBalanceFloor() != null
                    ? account.getBalanceFloor()
                    : properties.getBalanceFloor();

                StreamState state = StreamState.fromDbRow(txn, 0L);
                BigDecimal projection = StreamSettlementCalculator.computeProjection(state);
                BigDecimal estimatedBalance = account.getBalance().subtract(projection);

                if (estimatedBalance.compareTo(effectiveFloor) <= 0) {
                    log.info("Startup reconciliation: auto-terminating exhausted stream {}", txn.getStreamId());
                    autoTerminate(txn.getStreamId());
                    autoTerminated++;
                } else {
                    streamRegistry.register(state);
                    long delayMillis = AutoTerminationScheduler.calculateExhaustionDelayMillis(
                        estimatedBalance, effectiveFloor, txn.getRatePerSecond());
                    autoTerminationScheduler.enqueue(txn.getStreamId(), delayMillis);
                    reconciled++;
                }
            } catch (Exception e) {
                log.warn("Startup reconciliation error for stream {}: {}", txn.getStreamId(), e.getMessage());
            }
        }
        log.info("Startup reconciliation complete: {} reconciled, {} auto-terminated", reconciled, autoTerminated);
    }

    private BigDecimal computeElapsedSeconds(StreamState state) {
        if (state.startedAtNanoFromCurrentJvm()) {
            long elapsedNanos = System.nanoTime() - state.startedAtNano();
            return BigDecimal.valueOf(elapsedNanos)
                .divide(BigDecimal.valueOf(1_000_000_000L), 18, RoundingMode.DOWN);
        } else {
            long elapsedMillis = System.currentTimeMillis() - state.startedAtEpochMillis();
            return BigDecimal.valueOf(elapsedMillis)
                .divide(BigDecimal.valueOf(1000L), 18, RoundingMode.DOWN);
        }
    }

    private void storeIdempotencyKey(String key, String operation, Object response) {
        try {
            idempotencyKeyRepository.save(IdempotencyKey.builder()
                .idempotencyKey(key)
                .operation(operation)
                .responseBody(objectMapper.writeValueAsString(response))
                .createdAt(OffsetDateTime.now())
                .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist idempotency key", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached response", e);
        }
    }
}
