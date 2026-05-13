# Phase 2: Discrete Transactions - Pattern Map

**Mapped:** 2026-05-13
**Files analyzed:** 17 new/modified files
**Analogs found:** 17 / 17

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `engine-core/.../domain/DiscreteTransaction.java` | entity | CRUD | `engine-core/.../domain/BalanceAuditLog.java` | exact |
| `engine-core/.../domain/TransactionType.java` | model | — | `engine-core/.../domain/AccountStatus.java` | exact |
| `engine-core/.../domain/BalanceAuditLog.java` *(modify)* | entity | CRUD | self | exact |
| `engine-core/.../repository/AccountRepository.java` *(modify)* | repository | CRUD | self | exact |
| `engine-core/.../repository/DiscreteTransactionRepository.java` | repository | CRUD | `engine-core/.../repository/BalanceAuditLogRepository.java` | exact |
| `engine-core/.../dto/PostTransactionRequest.java` | dto | request-response | `engine-core/.../dto/CreateAccountRequest.java` | exact |
| `engine-core/.../dto/PostTransactionResponse.java` | dto | request-response | `engine-core/.../dto/AccountResponse.java` | exact |
| `engine-core/.../service/TransactionService.java` | service interface | request-response | `engine-core/.../service/AccountService.java` | exact |
| `engine-spring/.../service/TransactionServiceImpl.java` | service | CRUD | `engine-spring/.../service/AccountServiceImpl.java` | exact |
| `engine-spring/.../autoconfigure/TokenEngineAutoConfiguration.java` *(modify)* | config | — | self | exact |
| `engine-spring/.../config/TokenEngineProperties.java` *(modify)* | config | — | self | exact |
| `engine-service/.../controller/TransactionController.java` | controller | request-response | `engine-service/.../controller/AccountController.java` | exact |
| `engine-service/.../controller/GlobalExceptionHandler.java` *(modify)* | middleware | request-response | self | exact |
| `db/migration/V4__create_discrete_transactions.sql` | migration | — | `V3__create_balance_audit_log.sql` | exact |
| `db/migration/V5__add_transaction_id_to_audit_log.sql` | migration | — | `V3__create_balance_audit_log.sql` | role-match |
| `test/steps/TransactionSteps.java` | test | request-response | `test/steps/AccountSteps.java` | exact |
| `test/java/.../DiscreteTransactionConcurrencyTest.java` | test | request-response | `test/steps/IdempotencySteps.java` | role-match |
| `test/resources/features/discrete-credit.feature` | test | request-response | `test/resources/features/balance-floor.feature` | exact |
| `test/resources/features/discrete-debit.feature` | test | request-response | `test/resources/features/balance-floor.feature` | exact |
| `test/resources/features/discrete-metadata.feature` | test | request-response | `test/resources/features/audit-log.feature` | exact |
| `test/resources/features/discrete-rake.feature` | test | request-response | `test/resources/features/idempotency.feature` | role-match |

---

## Pattern Assignments

### `engine-core/.../domain/DiscreteTransaction.java` (entity, CRUD)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/domain/BalanceAuditLog.java`

**Imports pattern** (lines 1-14):
```java
package com.certacota.engine.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
```

**Core entity pattern** (BalanceAuditLog.java lines 17-49):
```java
@Entity
@Table(name = "balance_audit_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "operation", nullable = false, updatable = false, length = 50)
    private String operation;

    @Column(name = "amount", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal amount;
    // ... all columns immutable (updatable = false)
    @Column(name = "recorded_at", updatable = false)
    private OffsetDateTime recordedAt;
}
```

**JSONB metadata addition** — copy from `Account.java` lines 13-14 and 42-44:
```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
// ...
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "metadata", columnDefinition = "jsonb")
private Map<String, Object> metadata;
```

**Key differences from analog:** Add `@Enumerated(EnumType.STRING)` field for `TransactionType type`; add `Map<String,Object> metadata` with `@JdbcTypeCode(SqlTypes.JSON)`; keep all columns `updatable = false` (append-only ledger entry).

---

### `engine-core/.../domain/TransactionType.java` (enum)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/domain/AccountStatus.java`

Read `AccountStatus.java` — it is a simple package-private enum with no annotations. Apply the same pattern:
```java
package com.certacota.engine.core.domain;

public enum TransactionType {
    CREDIT,
    DEBIT
}
```

---

### `engine-core/.../domain/BalanceAuditLog.java` (modify — add transactionId field)

**Analog:** Self (existing file, lines 1-49 already read)

Add one nullable field after `idempotencyKey` (line 45 in current file):
```java
@Column(name = "transaction_id")
private Long transactionId;
```

No other changes. All existing columns stay `updatable = false`. The new field must also be `updatable = false` once written (audit entries are immutable).

---

### `engine-core/.../repository/AccountRepository.java` (modify — add findWithLock)

**Analog:** Self + Spring Data JPA `@Lock` pattern from RESEARCH.md

Current file (lines 1-7) has only `JpaRepository` extension. Add imports and the locking method:

```java
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
// ...
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findWithLock(@Param("id") String id);
```

**Critical constraint:** Only call `findWithLock` from inside a `@Transactional` service method. The lock is released at transaction commit/rollback. Use plain `findById` for read-only operations.

---

### `engine-core/.../repository/DiscreteTransactionRepository.java` (repository, CRUD)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/repository/BalanceAuditLogRepository.java` (lines 1-11)

```java
package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.BalanceAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BalanceAuditLogRepository extends JpaRepository<BalanceAuditLog, Long> {
    List<BalanceAuditLog> findByAccountId(String accountId);
}
```

Apply same pattern with `DiscreteTransaction` entity, `Long` ID, and a `findByAccountId` derived query. Also add `findByIdempotencyKey(String idempotencyKey)` for idempotency check — see idempotency pattern in `AccountServiceImpl.java` lines 42-48.

---

### `engine-core/.../dto/PostTransactionRequest.java` (dto, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/dto/CreateAccountRequest.java` (lines 1-13)

```java
package com.certacota.engine.core.dto;

import java.math.BigDecimal;
import java.util.Map;

public record CreateAccountRequest(
    String id,
    BigDecimal initialBalance,
    BigDecimal balanceFloor,
    Map<String, Object> metadata,
    String idempotencyKey
) {
}
```

Apply same `record` pattern. Fields for Phase 2: `String accountId`, `String type` (or `TransactionType type`), `BigDecimal amount`, `Map<String, Object> metadata`, `String idempotencyKey`, `String toAccountId` (nullable — rake path only).

---

### `engine-core/.../dto/PostTransactionResponse.java` (dto, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/dto/AccountResponse.java` (lines 1-29)

```java
public record AccountResponse(
    String id,
    String status,
    BigDecimal balance,
    // ...
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
            account.getId(),
            // ...
        );
    }
}
```

Apply same pattern: `record` with a static `from(DiscreteTransaction txn, BigDecimal balanceAfter)` factory method. Fields: `Long transactionId`, `String accountId`, `String type`, `BigDecimal amount`, `BigDecimal balanceAfter`, `Map<String, Object> metadata`, `OffsetDateTime postedAt`.

---

### `engine-core/.../service/TransactionService.java` (service interface, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/service/AccountService.java` (lines 1-10)

```java
package com.certacota.engine.core.service;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.CreateAccountRequest;

public interface AccountService {
    AccountResponse createAccount(CreateAccountRequest request);
    AccountResponse getAccount(String accountId);
    AccountResponse closeAccount(String accountId);
}
```

Apply same: minimal interface, no Spring annotations. Methods: `PostTransactionResponse credit(PostTransactionRequest request)` and `PostTransactionResponse debit(PostTransactionRequest request)`.

---

### `engine-spring/.../service/TransactionServiceImpl.java` (service, CRUD)

**Analog:** `engine-spring/src/main/java/com/certacota/engine/spring/service/AccountServiceImpl.java` (all 133 lines)

**Imports pattern** (lines 1-22):
```java
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
```

**Class declaration + DI pattern** (lines 28-36):
```java
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
```

**Idempotency check-first pattern** (lines 39-48):
```java
return idempotencyKeyRepository
    .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "ACCOUNT_CREATE")
    .map(ik -> {
        log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
        return deserialize(ik.getResponseBody(), AccountResponse.class);
    })
    .orElseGet(() -> doCreateAccount(request));
```

**Idempotency key persistence pattern** (lines 86-95):
```java
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
```

**Deserialize helper pattern** (lines 126-132):
```java
private <T> T deserialize(String json, Class<T> type) {
    try {
        return objectMapper.readValue(json, type);
    } catch (Exception e) {
        throw new IllegalStateException("Failed to deserialize cached response", e);
    }
}
```

**Key additions for TransactionServiceImpl (not in analog):**
- Inject `DiscreteTransactionRepository discreteTransactionRepository`
- Use `accountRepository.findWithLock(accountId)` (not `findById`) at start of every write method
- Floor check happens after lock is acquired (see RESEARCH.md Pattern 2, lines 226-232)
- Rake rate resolution via `properties.getRake().getRateFor(request.metadata())` (see RESEARCH.md Pattern 6)
- Operation strings: `"DISCRETE_CREDIT"` and `"DISCRETE_DEBIT"` for idempotency key scoping

---

### `engine-spring/.../autoconfigure/TokenEngineAutoConfiguration.java` (modify — add TransactionServiceImpl bean)

**Analog:** Self (lines 1-34)

```java
@Bean
@ConditionalOnMissingBean
public AccountService accountService(
        AccountRepository accountRepository,
        BalanceAuditLogRepository auditLogRepository,
        IdempotencyKeyRepository idempotencyKeyRepository,
        TokenEngineProperties properties,
        ObjectMapper objectMapper) {
    return new AccountServiceImpl(
        accountRepository, auditLogRepository, idempotencyKeyRepository, properties, objectMapper);
}
```

Copy this `@Bean` block exactly. Add a second `@Bean @ConditionalOnMissingBean` block for `TransactionService` with the same method shape, injecting `DiscreteTransactionRepository` as an additional parameter.

---

### `engine-spring/.../config/TokenEngineProperties.java` (modify — add RakeProperties)

**Analog:** Self (lines 1-15)

```java
@ConfigurationProperties(prefix = "token-engine")
@Getter
@Setter
public class TokenEngineProperties {

    private BigDecimal balanceFloor = BigDecimal.ZERO;
}
```

Add a nested `RakeProperties` inner static class. Add import for `HashMap` and `Map`. See RESEARCH.md Pattern 6 (lines 424-448) for the exact shape of `RakeProperties` including `getRateFor(Map<String, Object> metadata)` method. The inner class uses `@Getter @Setter` and defaults `enabled = false`.

---

### `engine-service/.../controller/TransactionController.java` (controller, request-response)

**Analog:** `engine-service/src/main/java/com/certacota/engine/service/controller/AccountController.java` (all 41 lines)

```java
package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.CreateAccountRequest;
import com.certacota.engine.core.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }
```

Apply same: `@RestController`, `@RequestMapping("/api/v1/transactions")`, `@RequiredArgsConstructor`, `@Slf4j`. Single `@PostMapping` method, `@ResponseStatus(HttpStatus.CREATED)`, delegates to `TransactionService`. No business logic in controller.

---

### `engine-service/.../controller/GlobalExceptionHandler.java` (modify — add new exception handlers)

**Analog:** Self (all 46 lines)

```java
@ExceptionHandler(AccountNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public Map<String, String> handleNotFound(AccountNotFoundException ex) {
    log.warn("Account not found: {}", ex.getMessage());
    return Map.of("error", ex.getMessage());
}

@ExceptionHandler(BalanceFloorViolationException.class)
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public Map<String, String> handleFloorViolation(BalanceFloorViolationException ex) {
    log.warn("Balance floor violation: {}", ex.getMessage());
    return Map.of("error", ex.getMessage());
}
```

Copy this `@ExceptionHandler` method shape exactly for any new Phase 2 exceptions (e.g., `InsufficientBalanceException` if introduced). `BalanceFloorViolationException` already handled — it covers the debit floor rejection. No new exception class is needed if the existing `BalanceFloorViolationException` is reused for the floor check.

---

### `db/migration/V4__create_discrete_transactions.sql` (migration)

**Analog:** `engine-service/src/main/resources/db/migration/V3__create_balance_audit_log.sql` (lines 1-12)

```sql
CREATE TABLE balance_audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    account_id      VARCHAR(255)    NOT NULL,
    operation       VARCHAR(50)     NOT NULL,
    amount          NUMERIC(38,18)  NOT NULL,
    balance_before  NUMERIC(38,18)  NOT NULL,
    balance_after   NUMERIC(38,18)  NOT NULL,
    idempotency_key VARCHAR(255),
    recorded_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);
```

Apply same DDL conventions: `BIGSERIAL PRIMARY KEY`, `VARCHAR(255)` for string IDs, `NUMERIC(38,18)` for monetary amounts, `TIMESTAMPTZ NOT NULL DEFAULT NOW()`, FK constraint named `fk_<table>_<target>`, CHECK constraints named `chk_<table>_<field>`. See RESEARCH.md lines 483-503 for the exact V4 DDL.

---

### `db/migration/V5__add_transaction_id_to_audit_log.sql` (migration)

**Analog:** `V3__create_balance_audit_log.sql` for conventions; `V1__create_accounts.sql` for ALTER TABLE pattern (not present in V1-V3, so this is a no-analog ALTER).

Pattern from RESEARCH.md lines 512-519:
```sql
ALTER TABLE balance_audit_log
    ADD COLUMN transaction_id BIGINT,
    ADD CONSTRAINT fk_audit_dtx
        FOREIGN KEY (transaction_id)
        REFERENCES discrete_transactions(id);
```

`transaction_id` is nullable (Phase 1 audit entries have no transaction). The `BalanceAuditLog` entity must be updated simultaneously with this migration (add `@Column(name = "transaction_id") private Long transactionId;`) or Hibernate schema validation will fail at startup.

---

### `test/steps/TransactionSteps.java` (test, request-response)

**Analog:** `engine-service/src/test/java/com/certacota/engine/service/steps/AccountSteps.java` (all 117 lines)

**Class declaration + fields pattern** (lines 30-54):
```java
@Slf4j
public class AccountSteps {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceAuditLogRepository auditLogRepository;

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;

    public AccountSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }
```

**HTTP call pattern** (lines 61-75):
```java
String url = "http://localhost:" + port + "/api/v1/accounts";
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
Map<String, Object> body = Map.of(
    "id", accountId,
    "initialBalance", balance,
    "idempotencyKey", "auto-" + accountId
);
HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
sharedContext.setLastResponse(response);
log.info("Create account response: {} {}", response.getStatusCode(), response.getBody());
```

**Balance assertion pattern** (lines 77-86):
```java
String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId;
ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
assertThat(response.getStatusCode().value()).isEqualTo(200);
ObjectMapper mapper = new ObjectMapper();
JsonNode json = mapper.readTree(response.getBody());
BigDecimal actualBalance = json.get("balance").decimalValue();
assertThat(actualBalance.compareTo(expectedBalance)).isEqualTo(0);
```

Copy all patterns. Change URL to `/api/v1/transactions`. Add `DiscreteTransactionRepository` as an `@Autowired` field for direct DB verification of persisted transaction records.

---

### `test/java/.../DiscreteTransactionConcurrencyTest.java` (test, request-response)

**Analog:** `IdempotencySteps.java` for RestTemplate setup pattern; RESEARCH.md Pattern 4 (lines 323-359) for the `ExecutorService + CountDownLatch` structure.

**RestTemplate setup** (IdempotencySteps.java lines 41-49):
```java
public IdempotencySteps() {
    restTemplate = new RestTemplate();
    restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }
    });
}
```

**Test class setup** — use `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `@Testcontainers` (same as `CucumberSpringConfiguration`). Use `@LocalServerPort int port`. Use `@Autowired AccountRepository accountRepository` for final balance check via committed DB state — not through HTTP GET (avoids read-through-cache ambiguity).

**Concurrency core pattern** from RESEARCH.md lines 327-359 — copy the `ExecutorService + CountDownLatch` skeleton verbatim.

---

### Feature files: `discrete-credit.feature`, `discrete-debit.feature`, `discrete-metadata.feature`, `discrete-rake.feature`

**Analog:** `engine-service/src/test/resources/features/balance-floor.feature` and `idempotency.feature`

**Feature file structure** (balance-floor.feature lines 1-12):
```gherkin
Feature: Balance floor enforcement

  Scenario: Creating account with initial balance below global floor is rejected
    Given the global balance floor is 0
    When I attempt to create an account with id "floor-001" and initialBalance -5.00
    Then the response status is 422

  Scenario: Per-account floor override takes precedence over global
    Given the global balance floor is 0
    When I attempt to create an account with id "floor-002" and initialBalance 40.00 and balanceFloor 50.00
    Then the response status is 422
```

Apply same: `Feature:` header, named `Scenario:` blocks, no `Background:` unless shared setup is needed across all scenarios in the file. Use concrete string/decimal literals in step names. Steps delegate to `TransactionSteps` methods — no business logic in `.feature` files.

---

## Shared Patterns

### Idempotency (check-then-store)
**Source:** `engine-spring/src/main/java/com/certacota/engine/spring/service/AccountServiceImpl.java` lines 42-95
**Apply to:** `TransactionServiceImpl.credit()` and `TransactionServiceImpl.debit()`

Pattern: `findByIdempotencyKeyAndOperation` before the write path; if found, deserialize and return cached response; if not found, execute the write, then `save` the `IdempotencyKey`. Use operation strings `"DISCRETE_CREDIT"` and `"DISCRETE_DEBIT"` (same scoping pattern as `"ACCOUNT_CREATE"`).

### Error handling (exception to HTTP status)
**Source:** `engine-service/src/main/java/com/certacota/engine/service/controller/GlobalExceptionHandler.java` lines 19-45
**Apply to:** Any new exceptions added to `GlobalExceptionHandler.java`

Pattern: `@ExceptionHandler(XException.class)` + `@ResponseStatus(HttpStatus.XXX)` + `log.warn(...)` + `return Map.of("error", ex.getMessage())`. Keep responses as `Map<String, String>` — no custom error body class.

### Logging
**Source:** `AccountServiceImpl.java` lines 40, 45, 59, 102, 111, 116
**Apply to:** `TransactionServiceImpl`

Pattern: `log.info("Posting {} transaction: {}", type, request.accountId())` at method entry; `log.warn(...)` for expected domain failures (floor violation, closed account); never `log.error(...)` for these.

### Audit log write (same transaction)
**Source:** `AccountServiceImpl.java` lines 74-83
**Apply to:** Every balance-modifying method in `TransactionServiceImpl`

```java
auditLogRepository.save(BalanceAuditLog.builder()
    .accountId(account.getId())
    .operation("ACCOUNT_CREATED")
    .amount(initialBalance)
    .balanceBefore(BigDecimal.ZERO)
    .balanceAfter(initialBalance)
    .idempotencyKey(request.idempotencyKey())
    .recordedAt(OffsetDateTime.now())
    .build());
```

In Phase 2, also set `.transactionId(txn.getId())` on the builder after V5 migration adds the column.

### Lombok DI pattern
**Source:** `AccountServiceImpl.java` lines 27-35; `AccountController.java` lines 22-24
**Apply to:** All new `@Service` and `@RestController` classes

Always use `@RequiredArgsConstructor` on the class + `private final` fields. Never use `@Autowired` field injection or constructor body injection.

### RestTemplate error suppression (tests)
**Source:** `AccountSteps.java` lines 46-53
**Apply to:** `TransactionSteps.java`, `DiscreteTransactionConcurrencyTest.java`

```java
restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
    @Override
    public boolean hasError(ClientHttpResponse response) {
        return false;
    }
});
```

Always suppress HTTP error throwing in test RestTemplate instances — step methods check `response.getStatusCode().value()` explicitly via `assertThat`.

### SharedContext for last-response
**Source:** `engine-service/src/test/java/com/certacota/engine/service/steps/SharedContext.java` (all 20 lines)
**Apply to:** `TransactionSteps.java`

`TransactionSteps` injects `SharedContext` via `@Autowired` (same as `AccountSteps.java` line 38) and calls `sharedContext.setLastResponse(response)` after every HTTP call. Assertions in `Then` steps read `sharedContext.getLastResponse()`.

---

## No Analog Found

All files have strong analogs in the Phase 1 codebase. No entries.

---

## Metadata

**Analog search scope:** `engine-core/src/main/java/`, `engine-spring/src/main/java/`, `engine-service/src/main/java/`, `engine-service/src/test/java/`, `engine-service/src/test/resources/features/`, `engine-service/src/main/resources/db/migration/`
**Files scanned:** 29 Java files, 5 feature files, 3 SQL files
**Pattern extraction date:** 2026-05-13
