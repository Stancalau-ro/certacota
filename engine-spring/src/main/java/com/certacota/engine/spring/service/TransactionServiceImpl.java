package com.certacota.engine.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.Account;
import com.certacota.engine.core.domain.AccountStatus;
import com.certacota.engine.core.domain.BalanceAuditLog;
import com.certacota.engine.core.domain.DiscreteTransaction;
import com.certacota.engine.core.domain.IdempotencyKey;
import com.certacota.engine.core.domain.TransactionType;
import com.certacota.engine.core.dto.PostTransactionRequest;
import com.certacota.engine.core.dto.PostTransactionResponse;
import com.certacota.engine.core.exception.AccountClosedException;
import com.certacota.engine.core.exception.AccountNotFoundException;
import com.certacota.engine.core.exception.BalanceFloorViolationException;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.DiscreteTransactionRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.service.TransactionService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final AccountRepository accountRepository;
    private final DiscreteTransactionRepository discreteTransactionRepository;
    private final BalanceAuditLogRepository auditLogRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TokenEngineProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public PostTransactionResponse credit(PostTransactionRequest request) {
        log.info("Processing CREDIT transaction for account: {}", request.accountId());
        return doCredit(request);
    }

    private PostTransactionResponse doCredit(PostTransactionRequest request) {
        Account account = accountRepository.findWithLock(request.accountId())
            .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

        // Idempotency check after lock acquisition to prevent duplicate execution under race conditions
        var existing = idempotencyKeyRepository
            .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_CREDIT");
        if (existing.isPresent()) {
            log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
            return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            log.warn("Attempt to credit closed account: {}", request.accountId());
            throw new AccountClosedException(request.accountId());
        }

        BigDecimal balanceBefore = account.getBalance();
        account.credit(request.amount());
        accountRepository.save(account);

        DiscreteTransaction txn = discreteTransactionRepository.save(DiscreteTransaction.builder()
            .accountId(request.accountId())
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
    public PostTransactionResponse debit(PostTransactionRequest request) {
        log.info("Processing DEBIT transaction for account: {}", request.accountId());
        return doDebit(request);
    }

    private PostTransactionResponse doDebit(PostTransactionRequest request) {
        Account account = accountRepository.findWithLock(request.accountId())
            .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

        // Idempotency check after lock acquisition to prevent duplicate execution under race conditions
        var existing = idempotencyKeyRepository
            .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_DEBIT");
        if (existing.isPresent()) {
            log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
            return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            log.warn("Attempt to debit closed account: {}", request.accountId());
            throw new AccountClosedException(request.accountId());
        }

        BigDecimal resultingBalance = account.getBalance().subtract(request.amount());
        BigDecimal effectiveFloor = account.getBalanceFloor() != null
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();
        if (resultingBalance.compareTo(effectiveFloor) < 0) {
            log.warn("Balance floor violation: debit of {} would bring balance to {}, below floor {}",
                request.amount(), resultingBalance, effectiveFloor);
            throw new BalanceFloorViolationException(
                "Debit of " + request.amount() + " would bring balance to " + resultingBalance
                    + ", below floor " + effectiveFloor);
        }

        BigDecimal rakeRate = properties.getRake().getRateFor(request.metadata());

        if (request.toAccountId() != null) {
            if (request.toAccountId().isBlank()) {
                throw new IllegalArgumentException("toAccountId must not be blank when provided");
            }
            return doDebitWithRake(request, account, rakeRate);
        }

        BigDecimal balanceBefore = account.getBalance();
        account.debit(request.amount());
        accountRepository.save(account);

        DiscreteTransaction txn = discreteTransactionRepository.save(DiscreteTransaction.builder()
            .accountId(request.accountId())
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

        PostTransactionResponse response = PostTransactionResponse.from(txn, account.getBalance());
        storeIdempotencyKey(request.idempotencyKey(), "DISCRETE_DEBIT", response);
        return response;
    }

    private PostTransactionResponse doDebitWithRake(PostTransactionRequest request, Account fromAccount, BigDecimal rakeRate) {
        BigDecimal effectiveFloor = fromAccount.getBalanceFloor() != null
            ? fromAccount.getBalanceFloor()
            : properties.getBalanceFloor();
        BigDecimal resultingBalance = fromAccount.getBalance().subtract(request.amount());
        if (resultingBalance.compareTo(effectiveFloor) < 0) {
            log.warn("Balance floor violation in rake path: debit of {} would bring balance to {}, below floor {}",
                request.amount(), resultingBalance, effectiveFloor);
            throw new BalanceFloorViolationException(
                "Debit of " + request.amount() + " would bring balance to " + resultingBalance
                    + ", below floor " + effectiveFloor);
        }

        BigDecimal rakeAmount = request.amount().multiply(rakeRate).setScale(18, RoundingMode.DOWN);
        BigDecimal toAccountAmount = request.amount().subtract(rakeAmount);

        // Lock order: from → to → platform (consistent ordering prevents deadlock)
        Account toAccount = accountRepository.findWithLock(request.toAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

        String platformAccountId = properties.getRake().getPlatformAccountId();
        Account platformAccount = null;
        if (platformAccountId != null && rakeAmount.compareTo(BigDecimal.ZERO) > 0) {
            platformAccount = accountRepository.findWithLock(platformAccountId)
                .orElseThrow(() -> new AccountNotFoundException(platformAccountId));
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
            .operation("DISCRETE_DEBIT")
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
        storeIdempotencyKey(request.idempotencyKey(), "DISCRETE_DEBIT", response);
        return response;
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
