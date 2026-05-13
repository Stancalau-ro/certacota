package io.certacota.engine.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.certacota.engine.core.domain.Account;
import io.certacota.engine.core.domain.AccountStatus;
import io.certacota.engine.core.domain.BalanceAuditLog;
import io.certacota.engine.core.domain.IdempotencyKey;
import io.certacota.engine.core.dto.AccountResponse;
import io.certacota.engine.core.dto.CreateAccountRequest;
import io.certacota.engine.core.exception.AccountClosedException;
import io.certacota.engine.core.exception.AccountNotFoundException;
import io.certacota.engine.core.exception.BalanceFloorViolationException;
import io.certacota.engine.core.repository.AccountRepository;
import io.certacota.engine.core.repository.BalanceAuditLogRepository;
import io.certacota.engine.core.repository.IdempotencyKeyRepository;
import io.certacota.engine.core.service.AccountService;
import io.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
        try {
            idempotencyKeyRepository.saveAndFlush(IdempotencyKey.builder()
                .idempotencyKey(request.idempotencyKey())
                .operation("ACCOUNT_CREATE")
                .responseBody("")
                .createdAt(OffsetDateTime.now())
                .build());

            BigDecimal initialBalance = request.initialBalance() != null
                ? request.initialBalance() : BigDecimal.ZERO;
            enforceBalanceFloor(null, initialBalance);

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

            storeIdempotencyResponse(request.idempotencyKey(), "ACCOUNT_CREATE", response);

            return response;

        } catch (DataIntegrityViolationException ex) {
            return idempotencyKeyRepository
                .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "ACCOUNT_CREATE")
                .map(ik -> deserialize(ik.getResponseBody(), AccountResponse.class))
                .orElseThrow(() -> new IllegalStateException(
                    "Constraint violation but no cached response found", ex));
        }
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

    private void enforceBalanceFloor(Account account, BigDecimal resultingBalance) {
        BigDecimal effectiveFloor = (account != null && account.getBalanceFloor() != null)
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();
        if (resultingBalance.compareTo(effectiveFloor) < 0) {
            log.warn("Balance floor violation: {} is below floor {}", resultingBalance, effectiveFloor);
            throw new BalanceFloorViolationException(
                "Balance " + resultingBalance + " is below floor " + effectiveFloor);
        }
    }

    private void storeIdempotencyResponse(String idempotencyKey, String operation, AccountResponse response) {
        idempotencyKeyRepository.findByIdempotencyKeyAndOperation(idempotencyKey, operation)
            .ifPresent(ik -> {
                try {
                    ik.updateResponseBody(objectMapper.writeValueAsString(response));
                    idempotencyKeyRepository.save(ik);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to serialize idempotency response", e);
                }
            });
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached response", e);
        }
    }
}
