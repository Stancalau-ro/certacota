package com.certacota.engine.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.Account;
import com.certacota.engine.core.domain.AccountStatus;
import com.certacota.engine.core.domain.BalanceAuditLog;
import com.certacota.engine.core.domain.DiscreteTransaction;
import com.certacota.engine.core.domain.IdempotencyKey;
import com.certacota.engine.core.domain.StreamSettlementCalculator;
import com.certacota.engine.core.domain.StreamState;
import com.certacota.engine.core.domain.StreamingTransaction;
import com.certacota.engine.core.domain.TransactionType;
import com.certacota.engine.core.dto.CreditRequest;
import com.certacota.engine.core.dto.DebitRequest;
import com.certacota.engine.core.dto.PostTransactionResponse;
import com.certacota.engine.core.dto.PostTransferRequest;
import com.certacota.engine.core.exception.AccountClosedException;
import com.certacota.engine.core.exception.AccountNotFoundException;
import com.certacota.engine.core.exception.BalanceFloorViolationException;
import com.certacota.engine.core.exception.RedisUnavailableException;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.DiscreteTransactionRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.TransactionService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.scheduler.AutoTerminationScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final AccountRepository accountRepository;
    private final DiscreteTransactionRepository discreteTransactionRepository;
    private final BalanceAuditLogRepository auditLogRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final StreamingTransactionRepository streamingTransactionRepository;
    private final StreamRegistry streamRegistry;
    private final TokenEngineProperties properties;
    private final ObjectMapper objectMapper;

    @Lazy
    @Autowired
    private AutoTerminationScheduler autoTerminationScheduler;

    @Override
    public PostTransactionResponse credit(String accountId, CreditRequest request) {
        log.info("Processing CREDIT transaction for account: {}", accountId);
        Account account = accountRepository.findWithLock(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

        // Idempotency check after lock acquisition to prevent duplicate execution under race conditions
        var existing = idempotencyKeyRepository
            .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_CREDIT");
        if (existing.isPresent()) {
            log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
            return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            log.warn("Attempt to credit closed account: {}", accountId);
            throw new AccountClosedException(accountId);
        }

        BigDecimal balanceBefore = account.getBalance();
        account.credit(request.amount());
        accountRepository.save(account);

        DiscreteTransaction txn = discreteTransactionRepository.save(DiscreteTransaction.builder()
            .accountId(accountId)
            .type(TransactionType.CREDIT)
            .amount(request.amount())
            .metadata(request.metadata())
            .idempotencyKey(request.idempotencyKey())
            .postedAt(OffsetDateTime.now())
            .build());

        auditLogRepository.save(BalanceAuditLog.builder()
            .accountId(account.getId())
            .operation("DISCRETE_CREDIT")
            .amount(request.amount())
            .balanceBefore(balanceBefore)
            .balanceAfter(account.getBalance())
            .idempotencyKey(request.idempotencyKey())
            .transactionId(txn.getId())
            .recordedAt(OffsetDateTime.now())
            .build());

        PostTransactionResponse response = PostTransactionResponse.from(txn, account.getBalance());
        storeIdempotencyKey(request.idempotencyKey(), "DISCRETE_CREDIT", response);
        return response;
    }

    @Override
    public PostTransactionResponse debit(String accountId, DebitRequest request) {
        log.info("Processing DEBIT transaction for account: {}", accountId);
        Account account = accountRepository.findWithLock(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

        // Idempotency check after lock acquisition to prevent duplicate execution under race conditions
        var existing = idempotencyKeyRepository
            .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_DEBIT");
        if (existing.isPresent()) {
            log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
            return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            log.warn("Attempt to debit closed account: {}", accountId);
            throw new AccountClosedException(accountId);
        }

        BigDecimal effectiveFloor = account.getBalanceFloor() != null
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();

        // Estimated balance accounts for active streams on this account (D-17)
        List<StreamState> activeStreams = getActiveStreamsWithFallback(accountId);

        BigDecimal totalProjected = activeStreams.stream()
            .map(StreamSettlementCalculator::computeProjection)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimatedBalance = account.getBalance().subtract(totalProjected);

        BigDecimal resultingEstimatedBalance = estimatedBalance.subtract(request.amount());
        if (resultingEstimatedBalance.compareTo(effectiveFloor) < 0) {
            log.warn("Balance floor violation: debit of {} would bring estimated balance to {}, below floor {}",
                request.amount(), resultingEstimatedBalance, effectiveFloor);
            throw new BalanceFloorViolationException(
                "Debit of " + request.amount() + " would bring estimated balance to " + resultingEstimatedBalance
                    + ", below floor " + effectiveFloor);
        }

        BigDecimal balanceBefore = account.getBalance();
        account.debit(request.amount());
        accountRepository.save(account);

        DiscreteTransaction txn = discreteTransactionRepository.save(DiscreteTransaction.builder()
            .accountId(accountId)
            .type(TransactionType.DEBIT)
            .amount(request.amount())
            .metadata(request.metadata())
            .idempotencyKey(request.idempotencyKey())
            .postedAt(OffsetDateTime.now())
            .build());

        auditLogRepository.save(BalanceAuditLog.builder()
            .accountId(account.getId())
            .operation("DISCRETE_DEBIT")
            .amount(request.amount())
            .balanceBefore(balanceBefore)
            .balanceAfter(account.getBalance())
            .idempotencyKey(request.idempotencyKey())
            .transactionId(txn.getId())
            .recordedAt(OffsetDateTime.now())
            .build());

        // Reschedule all active streams with updated exhaustion times after balance change (D-27)
        rescheduleActiveStreams(accountId, account.getBalance(), effectiveFloor);

        PostTransactionResponse response = PostTransactionResponse.from(txn, account.getBalance());
        storeIdempotencyKey(request.idempotencyKey(), "DISCRETE_DEBIT", response);
        return response;
    }

    @Override
    public PostTransactionResponse transfer(PostTransferRequest request) {
        log.info("Processing transfer of {} from account: {} to account: {}", request.amount(), request.accountId(), request.toAccountId());
        Account fromAccount = accountRepository.findWithLock(request.accountId())
            .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

        // Idempotency check after lock acquisition to prevent duplicate execution under race conditions
        var existing = idempotencyKeyRepository
            .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_TRANSFER");
        if (existing.isPresent()) {
            log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
            return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
        }

        if (fromAccount.getStatus() == AccountStatus.CLOSED) {
            log.warn("Attempt to transfer from closed account: {}", request.accountId());
            throw new AccountClosedException(request.accountId());
        }

        BigDecimal effectiveFloor = fromAccount.getBalanceFloor() != null
            ? fromAccount.getBalanceFloor()
            : properties.getBalanceFloor();
        BigDecimal resultingBalance = fromAccount.getBalance().subtract(request.amount());
        if (resultingBalance.compareTo(effectiveFloor) < 0) {
            log.warn("Balance floor violation: transfer of {} would bring balance to {}, below floor {}",
                request.amount(), resultingBalance, effectiveFloor);
            throw new BalanceFloorViolationException(
                "Transfer of " + request.amount() + " would bring balance to " + resultingBalance
                    + ", below floor " + effectiveFloor);
        }

        BigDecimal rakeRate = request.rakeRate() != null ? request.rakeRate() : BigDecimal.ZERO;
        BigDecimal rakeAmount = request.amount().multiply(rakeRate).setScale(18, RoundingMode.DOWN);
        BigDecimal toAccountAmount = request.amount().subtract(rakeAmount);

        // Lock order: from → to → platform (consistent ordering prevents deadlock)
        Account toAccount = accountRepository.findWithLock(request.toAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

        Account platformAccount = null;
        if (request.platformAccountId() != null && rakeAmount.compareTo(BigDecimal.ZERO) > 0) {
            platformAccount = accountRepository.findWithLock(request.platformAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.platformAccountId()));
        }

        BigDecimal fromBalanceBefore = fromAccount.getBalance();
        fromAccount.debit(request.amount());
        accountRepository.save(fromAccount);

        BigDecimal toBalanceBefore = toAccount.getBalance();
        toAccount.credit(toAccountAmount);
        accountRepository.save(toAccount);

        DiscreteTransaction txn = discreteTransactionRepository.save(DiscreteTransaction.builder()
            .accountId(request.accountId())
            .type(TransactionType.DEBIT)
            .amount(request.amount())
            .metadata(request.metadata())
            .idempotencyKey(request.idempotencyKey())
            .postedAt(OffsetDateTime.now())
            .build());

        auditLogRepository.save(BalanceAuditLog.builder()
            .accountId(fromAccount.getId())
            .operation("DISCRETE_TRANSFER")
            .amount(request.amount())
            .balanceBefore(fromBalanceBefore)
            .balanceAfter(fromAccount.getBalance())
            .idempotencyKey(request.idempotencyKey())
            .transactionId(txn.getId())
            .recordedAt(OffsetDateTime.now())
            .build());

        auditLogRepository.save(BalanceAuditLog.builder()
            .accountId(toAccount.getId())
            .operation("DISCRETE_CREDIT")
            .amount(toAccountAmount)
            .balanceBefore(toBalanceBefore)
            .balanceAfter(toAccount.getBalance())
            .idempotencyKey(request.idempotencyKey())
            .transactionId(txn.getId())
            .recordedAt(OffsetDateTime.now())
            .build());

        if (platformAccount != null) {
            BigDecimal platformBalanceBefore = platformAccount.getBalance();
            platformAccount.credit(rakeAmount);
            accountRepository.save(platformAccount);

            auditLogRepository.save(BalanceAuditLog.builder()
                .accountId(platformAccount.getId())
                .operation("DISCRETE_CREDIT")
                .amount(rakeAmount)
                .balanceBefore(platformBalanceBefore)
                .balanceAfter(platformAccount.getBalance())
                .idempotencyKey(request.idempotencyKey())
                .transactionId(txn.getId())
                .recordedAt(OffsetDateTime.now())
                .build());
        }

        PostTransactionResponse response = PostTransactionResponse.from(txn, fromAccount.getBalance());
        storeIdempotencyKey(request.idempotencyKey(), "DISCRETE_TRANSFER", response);
        return response;
    }

    private List<StreamState> getActiveStreamsWithFallback(String accountId) {
        try {
            return streamRegistry.getActiveStreams(accountId);
        } catch (DataAccessResourceFailureException e) {
            log.warn("Redis unavailable during debit floor check for account {}: {}", accountId, e.getMessage());
            if (streamingTransactionRepository.existsByAccountIdAndStatus(accountId, StreamingTransaction.ACTIVE)) {
                throw new RedisUnavailableException(
                    "Redis unavailable; cannot verify streaming floor for account " + accountId);
            }
            return Collections.emptyList();
        }
    }

    private void rescheduleActiveStreams(String accountId, BigDecimal committedBalance, BigDecimal effectiveFloor) {
        try {
            List<StreamState> activeStreams = streamRegistry.getActiveStreams(accountId);
            for (StreamState stream : activeStreams) {
                autoTerminationScheduler.cancel(stream.streamId());
                BigDecimal projection = StreamSettlementCalculator.computeProjection(stream);
                BigDecimal estimatedRemainingBalance = committedBalance.subtract(projection);
                long delayMillis = AutoTerminationScheduler.calculateExhaustionDelayMillis(
                    estimatedRemainingBalance, effectiveFloor, stream.ratePerSecond());
                autoTerminationScheduler.enqueue(stream.streamId(), delayMillis);
            }
        } catch (Exception e) {
            log.warn("Failed to reschedule active streams for account {} after debit — fallback sweep will handle: {}",
                accountId, e.getMessage());
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
