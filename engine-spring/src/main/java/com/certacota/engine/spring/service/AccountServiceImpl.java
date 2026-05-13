package com.certacota.engine.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.Account;
import com.certacota.engine.core.domain.AccountStatus;
import com.certacota.engine.core.domain.BalanceAuditLog;
import com.certacota.engine.core.domain.IdempotencyKey;
import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.CreateAccountRequest;
import com.certacota.engine.core.exception.AccountClosedException;
import com.certacota.engine.core.exception.AccountNotFoundException;
import com.certacota.engine.core.exception.BalanceFloorViolationException;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.service.AccountService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final BalanceAuditLogRepository auditLogRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TokenEngineProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public AccountResponse createAccount(CreateAccountRequest request) {
        log.info("Creating account: {}", request.id());

        return idempotencyKeyRepository
            .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "ACCOUNT_CREATE")
            .map(ik -> {
                log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
                return deserialize(ik.getResponseBody(), AccountResponse.class);
            })
            .orElseGet(() -> doCreateAccount(request));
    }

    private AccountResponse doCreateAccount(CreateAccountRequest request) {
        BigDecimal initialBalance = request.initialBalance() != null
            ? request.initialBalance() : BigDecimal.ZERO;

        BigDecimal effectiveFloor = request.balanceFloor() != null
            ? request.balanceFloor()
            : properties.getBalanceFloor();
        if (initialBalance.compareTo(effectiveFloor) < 0) {
            log.warn("Balance floor violation at creation: {} is below floor {}", initialBalance, effectiveFloor);
            throw new BalanceFloorViolationException(
                "Balance " + initialBalance + " is below floor " + effectiveFloor);
        }

        Account account = accountRepository.save(Account.builder()
            .id(request.id())
            .status(AccountStatus.ACTIVE)
            .balance(initialBalance)
            .balanceFloor(request.balanceFloor())
            .metadata(request.metadata())
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build());

        auditLogRepository.save(BalanceAuditLog.builder()
            .accountId(account.getId())
            .operation("ACCOUNT_CREATED")
            .amount(initialBalance)
            .balanceBefore(BigDecimal.ZERO)
            .balanceAfter(initialBalance)
            .idempotencyKey(request.idempotencyKey())
            .recordedAt(OffsetDateTime.now())
            .build());

        AccountResponse response = AccountResponse.from(account);

        try {
            idempotencyKeyRepository.save(IdempotencyKey.builder()
                .idempotencyKey(request.idempotencyKey())
                .operation("ACCOUNT_CREATE")
                .responseBody(objectMapper.writeValueAsString(response))
                .createdAt(OffsetDateTime.now())
                .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist idempotency key", e);
        }

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        log.info("Retrieving account: {}", accountId);
        return accountRepository.findById(accountId)
            .map(AccountResponse::from)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Override
    public AccountResponse closeAccount(String accountId) {
        log.info("Closing account: {}", accountId);
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (account.getStatus() == AccountStatus.CLOSED) {
            log.warn("Attempt to close already-closed account: {}", accountId);
            throw new AccountClosedException(accountId);
        }

        // Phase 3 will add: if streamRegistry.hasActiveStreams(accountId) throw AccountClosedException
        account.close();
        Account saved = accountRepository.save(account);
        return AccountResponse.from(saved);
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached response", e);
        }
    }
}
