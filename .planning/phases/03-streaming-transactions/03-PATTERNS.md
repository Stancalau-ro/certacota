# Phase 3: Streaming Transactions - Pattern Map

**Mapped:** 2026-05-14
**Files analyzed:** 28
**Analogs found:** 26 / 28

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql` | migration | batch | `V4__create_discrete_transactions.sql` | exact |
| `engine-service/src/main/resources/db/migration/V8__create_shedlock.sql` | migration | batch | `V1__create_accounts.sql` | role-match |
| `engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql` | migration | batch | `V3__create_balance_audit_log.sql` | role-match |
| `engine-core/src/main/java/.../domain/StreamingTransaction.java` | model | CRUD | `DiscreteTransaction.java` | exact |
| `engine-core/src/main/java/.../domain/StreamState.java` | model | event-driven | `DiscreteTransaction.java` | role-match |
| `engine-core/src/main/java/.../repository/StreamingTransactionRepository.java` | repository | CRUD | `DiscreteTransactionRepository.java` | exact |
| `engine-core/src/main/java/.../service/StreamingService.java` | service | request-response | `TransactionService.java` | exact |
| `engine-core/src/main/java/.../service/StreamRegistry.java` | service | event-driven | `AccountService.java` | role-match |
| `engine-core/src/main/java/.../dto/StartStreamRequest.java` | DTO | request-response | `CreditRequest.java` | exact |
| `engine-core/src/main/java/.../dto/StartStreamResponse.java` | DTO | request-response | `PostTransactionResponse.java` | exact |
| `engine-core/src/main/java/.../dto/StopStreamRequest.java` | DTO | request-response | `CreditRequest.java` | exact |
| `engine-core/src/main/java/.../dto/StopStreamResponse.java` | DTO | request-response | `PostTransactionResponse.java` | exact |
| `engine-core/src/main/java/.../dto/EstimatedBalanceResponse.java` | DTO | request-response | `PostTransactionResponse.java` | role-match |
| `engine-core/src/main/java/.../exception/StreamNotFoundException.java` | utility | request-response | `AccountNotFoundException.java` | exact |
| `engine-core/src/main/java/.../exception/RedisUnavailableException.java` | utility | request-response | `AccountNotFoundException.java` | role-match |
| `engine-spring/src/main/java/.../redis/RedisStreamRegistry.java` | service | event-driven | none | no analog |
| `engine-spring/src/main/java/.../service/StreamingServiceImpl.java` | service | request-response | `TransactionServiceImpl.java` | exact |
| `engine-spring/src/main/java/.../service/TransactionServiceImpl.java` (MODIFY) | service | request-response | `TransactionServiceImpl.java` | exact |
| `engine-spring/src/main/java/.../service/AccountServiceImpl.java` (MODIFY) | service | request-response | `AccountServiceImpl.java` | exact |
| `engine-spring/src/main/java/.../scheduler/AutoTerminationScheduler.java` | service | event-driven | none | no analog |
| `engine-spring/src/main/java/.../scheduler/FallbackSweepJob.java` | service | batch | none — use RESEARCH.md pattern | no analog |
| `engine-spring/src/main/java/.../scheduler/AuditArchivalJob.java` | service | batch | none — use RESEARCH.md pattern | no analog |
| `engine-spring/src/main/java/.../config/TokenEngineProperties.java` (MODIFY) | config | request-response | `TokenEngineProperties.java` | exact |
| `engine-spring/src/main/java/.../autoconfigure/TokenEngineAutoConfiguration.java` (MODIFY) | config | request-response | `TokenEngineAutoConfiguration.java` | exact |
| `engine-service/src/main/java/.../controller/StreamController.java` | controller | request-response | `AccountController.java` | exact |
| `engine-service/src/main/java/.../controller/EstimationController.java` | controller | request-response | `AccountController.java` | exact |
| `engine-service/src/main/java/.../controller/GlobalExceptionHandler.java` (MODIFY) | middleware | request-response | `GlobalExceptionHandler.java` | exact |
| `engine-service/src/test/java/.../TestcontainersConfiguration.java` (MODIFY) | config | batch | `TestcontainersConfiguration.java` | exact |
| `engine-service/src/test/java/.../steps/StreamingSteps.java` | test | request-response | `TransactionSteps.java` | exact |
| `engine-service/src/test/java/.../StreamingConcurrencyTest.java` | test | request-response | `DiscreteTransactionConcurrencyTest.java` | exact |
| `engine-service/src/test/resources/features/streaming-*.feature` (6 files) | test | request-response | `discrete-credit.feature` | exact |

---

## Pattern Assignments

### `V7__create_streaming_transactions.sql` (migration, batch)

**Analog:** `engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql`

**Core DDL pattern** (lines 1-15):
```sql
CREATE TABLE discrete_transactions (
    id              BIGSERIAL       PRIMARY KEY,
    account_id      VARCHAR(255)    NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    amount          NUMERIC(38,18)  NOT NULL,
    metadata        JSONB,
    idempotency_key VARCHAR(255),
    posted_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_dtx_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_dtx_type CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_dtx_amount CHECK (amount > 0)
);

CREATE INDEX idx_dtx_account_id ON discrete_transactions(account_id);
```

Copy this pattern for `streaming_transactions`. Fields differ (see RESEARCH.md DDL §V7), but constraint naming (`pk_`, `uq_`, `fk_`, `chk_`, `idx_` prefixes), `NUMERIC(38,18)` for token amounts, `TIMESTAMPTZ` for timestamps, and `BIGSERIAL PRIMARY KEY` all apply directly.

---

### `V8__create_shedlock.sql` (migration, batch)

**Analog:** `engine-service/src/main/resources/db/migration/V1__create_accounts.sql`

Copy the plain `CREATE TABLE` pattern (no Flyway-specific annotations). Use the exact DDL from RESEARCH.md §Pattern 4 — ShedLock has a fixed required schema that must not be altered.

---

### `V9__create_audit_archive.sql` (migration, batch)

**Analog:** `engine-service/src/main/resources/db/migration/V3__create_balance_audit_log.sql`

Use plain `CREATE TABLE` pattern. Mirror `balance_audit_log` columns exactly but in `audit_archive` schema (no FK constraints back to public schema). See RESEARCH.md §V9 DDL for complete column list.

---

### `engine-core/.../domain/StreamingTransaction.java` (model, CRUD)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java`

**Imports pattern** (lines 1-20):
```java
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

**Core entity pattern** (lines 22-53):
```java
@Entity
@Table(name = "discrete_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscreteTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "posted_at", updatable = false)
    private OffsetDateTime postedAt;
}
```

For `StreamingTransaction`: same annotations, same `precision = 38, scale = 18` on all BigDecimal columns, same `updatable = false` on immutable fields. Add `status`, `ratePerSecond`, `minimumAmount`, `increment`, `startedAt`, `stoppedAt`, `settledAmount`, `reason` columns. No `@JdbcTypeCode(SqlTypes.JSON)` needed (no metadata field on streaming transactions in Phase 3).

---

### `engine-core/.../domain/StreamState.java` (model, event-driven)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java` (role-match)

`StreamState` is NOT a JPA entity — it is a value object (record or plain class) representing in-memory Redis state. Use Java record syntax consistent with the DTO pattern below.

```java
public record StreamState(
    String streamId,
    String accountId,
    BigDecimal ratePerSecond,
    long startedAtEpochMillis,
    long startedAtNano,
    boolean startedAtNanoFromCurrentJvm,
    BigDecimal minimumAmount,
    BigDecimal increment
) {
    public static StreamState fromRedis(String streamId, Map<Object, Object> fields) { ... }
}
```

No JPA imports. No Lombok `@Builder` required since it is a record. The `startedAtNanoFromCurrentJvm` flag is set to `true` only when the current JVM registered the stream (detect by comparing `startedAtNano` to a JVM-startup nanoTime baseline stored in a static field in `RedisStreamRegistry`).

---

### `engine-core/.../repository/StreamingTransactionRepository.java` (repository, CRUD)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/repository/DiscreteTransactionRepository.java`

**Full pattern** (lines 1-14):
```java
public interface DiscreteTransactionRepository extends JpaRepository<DiscreteTransaction, Long> {

    List<DiscreteTransaction> findByAccountId(String accountId);

    Optional<DiscreteTransaction> findByIdempotencyKey(String idempotencyKey);
}
```

For `StreamingTransactionRepository`:
- `findByStreamId(String streamId)` → `Optional<StreamingTransaction>`
- `findByAccountIdAndStatus(String accountId, String status)` → `List<StreamingTransaction>`
- `findByStatus(String status)` → `List<StreamingTransaction>` (used in startup reconciliation and fallback sweep)

---

### `engine-core/.../service/StreamingService.java` (service interface, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/service/TransactionService.java`

**Full pattern** (lines 1-12):
```java
public interface TransactionService {
    PostTransactionResponse credit(String accountId, CreditRequest request);
    PostTransactionResponse debit(String accountId, DebitRequest request);
    PostTransactionResponse transfer(PostTransferRequest request);
}
```

For `StreamingService`:
```java
public interface StreamingService {
    StartStreamResponse startStream(StartStreamRequest request);
    StopStreamResponse stopStream(String streamId, StopStreamRequest request);
    EstimatedBalanceResponse estimateBalance(String accountId);
    void autoTerminate(String streamId);
}
```

No implementation code in the interface. Return types are records. Method signatures mirror the HTTP API endpoints.

---

### `engine-core/.../service/StreamRegistry.java` (service interface, event-driven)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/service/AccountService.java` (role-match)

Port interface in engine-core; implementation in engine-spring. No Spring or Redis imports in this interface.

```java
public interface StreamRegistry {
    void register(StreamState state);
    Optional<StreamState> get(String streamId);
    void remove(String streamId, String accountId);
    List<StreamState> getActiveStreams(String accountId);
    boolean hasActiveStreams(String accountId);
}
```

---

### `engine-core/.../dto/StartStreamRequest.java` (DTO, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/dto/CreditRequest.java`

**Full pattern** (lines 1-14):
```java
public record CreditRequest(
    @NotNull @Positive BigDecimal amount,
    @NotNull String idempotencyKey,
    Map<String, Object> metadata
) {
}
```

For `StartStreamRequest`:
```java
public record StartStreamRequest(
    @NotBlank String streamId,
    @NotBlank String accountId,
    @NotNull @Positive BigDecimal ratePerSecond,
    @NotBlank String idempotencyKey,
    @Positive BigDecimal minimumAmount,
    @Positive BigDecimal increment
) {
}
```

No static factory methods in request records. Validation via `jakarta.validation.constraints.*`. Optional fields (`minimumAmount`, `increment`) have no `@NotNull` — null is valid.

---

### `engine-core/.../dto/StartStreamResponse.java` (DTO, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionResponse.java`

**Full pattern** (lines 1-29):
```java
public record PostTransactionResponse(
    Long transactionId,
    String accountId,
    String type,
    BigDecimal amount,
    BigDecimal balanceAfter,
    Map<String, Object> metadata,
    OffsetDateTime postedAt
) {
    public static PostTransactionResponse from(DiscreteTransaction txn, BigDecimal balanceAfter) {
        return new PostTransactionResponse(...);
    }
}
```

For `StartStreamResponse`: record with `streamId`, `accountId`, `ratePerSecond`, `startedAt` (OffsetDateTime), plus `minimumAmount` and `increment` (nullable). Include a static `from(StreamingTransaction txn)` factory method consistent with the pattern.

---

### `engine-core/.../dto/StopStreamRequest.java` + `StopStreamResponse.java` (DTO, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/dto/CreditRequest.java` and `PostTransactionResponse.java`

`StopStreamRequest`: record with `ignoreMinimum` (boolean, default false — use primitive `boolean` not `Boolean` so `@RequestBody` deserialization defaults to false), `reason` (nullable String).

`StopStreamResponse`: record with `settledAmount` (BigDecimal), `stoppedAt` (OffsetDateTime), `reason` (String). No `finalBalance` per CONTEXT.md D-10.

---

### `engine-core/.../dto/EstimatedBalanceResponse.java` (DTO, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionResponse.java` (role-match)

```java
public record EstimatedBalanceResponse(
    BigDecimal estimatedBalance,
    BigDecimal committedBalance,
    OffsetDateTime estimatedAt,
    Long estimatedDrainAt
) {
}
```

`estimatedDrainAt` is `Long` (nullable Unix epoch millis), not `OffsetDateTime` or `long`. Per CONTEXT.md specifics: null when no active streams.

---

### `engine-core/.../exception/StreamNotFoundException.java` (utility, request-response)

**Analog:** `engine-core/src/main/java/com/certacota/engine/core/exception/AccountNotFoundException.java`

**Full pattern** (lines 1-7):
```java
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
```

Copy exactly for `StreamNotFoundException(String streamId)`. Also create `RedisUnavailableException(String message)` using the same pattern — no additional fields, single-arg constructor, extends `RuntimeException`.

---

### `engine-spring/.../redis/RedisStreamRegistry.java` (service, event-driven)

**Analog:** None — no Redis components exist in the codebase. Use RESEARCH.md Pattern 1 directly.

Key points from RESEARCH.md Pattern 1 (lines 297-355):
- Class-level: `@Component @RequiredArgsConstructor @Slf4j`
- Inject `RedisTemplate<String, String> redisTemplate`
- Constants: `private static final String STREAM_KEY_PREFIX = "stream:"` and `ACCOUNT_STREAMS_PREFIX = "account-streams:"`
- `register()`: `redisTemplate.opsForHash().putAll(streamKey, fields)` + `redisTemplate.opsForSet().add(accountStreamsKey, streamId)`
- `remove()`: `redisTemplate.delete(streamKey)` + `redisTemplate.opsForSet().remove(accountStreamsKey, streamId)`
- `getActiveStreams()`: `redisTemplate.opsForSet().members(accountStreamsKey)` → stream over IDs → `opsForHash().entries(streamKey)` → `StreamState.fromRedis()`
- Track JVM-start nanoTime as `private static final long JVM_START_NANO = System.nanoTime()` to enable `startedAtNanoFromCurrentJvm` detection: set `true` when registering, `false` when loading from Postgres on startup reconciliation.

Wrap all Redis operations in `try { ... } catch (RedisConnectionFailureException e)` — propagate as `RedisUnavailableException` from streaming paths; return fallback (empty list / false) from readonly paths called inside `@Transactional` during discrete debit.

---

### `engine-spring/.../service/StreamingServiceImpl.java` (service, request-response)

**Analog:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java`

**Imports pattern** (lines 1-30):
```java
import com.certacota.engine.core.domain.Account;
import com.certacota.engine.core.domain.AccountStatus;
import com.certacota.engine.core.domain.BalanceAuditLog;
import com.certacota.engine.core.domain.IdempotencyKey;
import com.certacota.engine.core.exception.AccountNotFoundException;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
```

**Service class declaration** (lines 32-36):
```java
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {
```

**Core lock + idempotency pattern** (lines 46-57):
```java
Account account = accountRepository.findWithLock(accountId)
    .orElseThrow(() -> new AccountNotFoundException(accountId));

var existing = idempotencyKeyRepository
    .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_CREDIT");
if (existing.isPresent()) {
    log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
    return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
}
```

**Audit log write pattern** (lines 77-86):
```java
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
```

**Idempotency store pattern** (lines 259-270):
```java
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
```

For `StreamingServiceImpl`:
- `startStream()` follows the same lock → idempotency check → status check → floor check → entity save → StreamRegistry.register() → AutoTerminationScheduler.enqueue() → audit log → idempotency store sequence
- `stopStream()` follows lock → StreamRegistry.get() → computeSettledAmount() → clampToAvailableBalance() → account.debit() → streamingTransactionRepo update → audit log → StreamRegistry.remove() → AutoTerminationScheduler.cancel()
- `estimateBalance()` uses `@Transactional(readOnly = true)` and `accountRepository.findById()` (no lock needed for read-only estimation)
- `autoTerminate()` delegates to `stopStream()` with `ignoreMinimum=true` and `reason="balance_exhaustion"` — marks stream as settled, used by `AutoTerminationScheduler` consumer thread
- `implements ApplicationListener<ApplicationReadyEvent>` for startup reconciliation (D-16/D-25)

**BigDecimal floor check pattern** (lines 112-122):
```java
BigDecimal resultingBalance = account.getBalance().subtract(request.amount());
BigDecimal effectiveFloor = account.getBalanceFloor() != null
    ? account.getBalanceFloor()
    : properties.getBalanceFloor();
if (resultingBalance.compareTo(effectiveFloor) < 0) {
    log.warn("Balance floor violation: debit of {} would bring balance to {}, below floor {}",
        request.amount(), resultingBalance, effectiveFloor);
    throw new BalanceFloorViolationException(...);
}
```

For streaming: use estimated balance (`committed - Σ projections`) instead of `account.getBalance()` for floor checks. The clamp path (D-19) replaces the throw at settlement time.

**RoundingMode pattern** (lines 184-185):
```java
BigDecimal rakeAmount = request.amount().multiply(rakeRate).setScale(18, RoundingMode.DOWN);
```

Apply `setScale(18, RoundingMode.DOWN)` to every intermediate BigDecimal result in settlement arithmetic.

---

### `engine-spring/.../service/TransactionServiceImpl.java` (MODIFY, service, request-response)

**Analog:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java`

**Modification scope:** `debit()` method only (lines 94-151). Add StreamRegistry consultation between idempotency check and balance floor check.

Add after line 110 (status check passes):
```java
// Estimated balance accounts for active streams on this account (D-17)
BigDecimal activeStreamProjection = streamRegistry.getActiveStreamsProjection(accountId);
BigDecimal estimatedBalance = account.getBalance().subtract(activeStreamProjection);
BigDecimal resultingEstimatedBalance = estimatedBalance.subtract(request.amount());
BigDecimal effectiveFloor = account.getBalanceFloor() != null
    ? account.getBalanceFloor() : properties.getBalanceFloor();
if (resultingEstimatedBalance.compareTo(effectiveFloor) < 0) {
    throw new BalanceFloorViolationException(...);
}
```

Note: `streamRegistry.getActiveStreamsProjection()` is a read-only Redis call that returns the sum of projections. If Redis is unavailable AND Postgres `streaming_transactions` has ACTIVE rows for this account, throw `RedisUnavailableException` (503) per D-30. If no ACTIVE rows exist in Postgres, allow the debit (no streaming state to account for).

---

### `engine-spring/.../service/AccountServiceImpl.java` (MODIFY, service, request-response)

**Analog:** `engine-spring/src/main/java/com/certacota/engine/spring/service/AccountServiceImpl.java`

**Modification scope:** `closeAccount()` method only (lines 110-124). Replace the Phase 3 placeholder comment at line 121 with actual stream check.

```java
// Replace line 121 comment with:
boolean hasActiveStreams = streamRegistry.hasActiveStreams(accountId);
if (hasActiveStreams) {
    log.warn("Cannot close account with active streams: {}", accountId);
    throw new AccountClosedException(accountId);
}
```

Redis-down fallback: if `RedisConnectionFailureException` is thrown by `streamRegistry.hasActiveStreams()`, check `streamingTransactionRepository.existsByAccountIdAndStatus(accountId, "ACTIVE")`. If true, throw `RedisUnavailableException` (503) per D-32. If false, allow close.

---

### `engine-spring/.../scheduler/AutoTerminationScheduler.java` (service, event-driven)

**Analog:** None — no scheduler components exist. Use RESEARCH.md Pattern 3 directly.

Key implementation notes from RESEARCH.md Pattern 3 (lines 408-465):
- `@Component @RequiredArgsConstructor @Slf4j`
- `implements ApplicationListener<ApplicationReadyEvent>`
- Fields: `RedissonClient redissonClient`, `StreamingService streamingService`
- `onApplicationEvent()`: initialize `destinationQueue = redissonClient.getBlockingDeque(EXHAUSTION_QUEUE)` + `delayedQueue = redissonClient.getDelayedQueue(destinationQueue)` + `startConsumerThread()`
- Consumer thread: `Thread.ofVirtual().name("auto-termination-consumer").start(...)` with `while(!interrupted)` loop and `try { destinationQueue.take(); streamingService.autoTerminate(streamId); } catch (InterruptedException) { interrupt(); } catch (Exception) { log.warn(...); }` — never let exceptions propagate
- `enqueue(String streamId, long delayMillis)`: `delayedQueue.offer(streamId, delayMillis, TimeUnit.MILLISECONDS)`
- `cancel(String streamId)`: `delayedQueue.remove(streamId)` — always call before re-enqueue
- Exhaustion time calculation: see RESEARCH.md lines 458-464

---

### `engine-spring/.../scheduler/FallbackSweepJob.java` (service, batch)

**Analog:** None — use RESEARCH.md Pattern 4 (ShedLock) directly.

Class-level annotations: `@Component @RequiredArgsConstructor @Slf4j`
Method: `@Scheduled(fixedDelayString = "${token-engine.streaming.fallback-sweep-seconds:300}000")` + `@SchedulerLock(name = "streaming_fallback_sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")`.

First line of method body: `LockAssert.assertLocked();`

Logic: query `streamingTransactionRepository.findByStatus("ACTIVE")`, for each compute `elapsedSeconds` using wall-clock (`startedAt`), compute estimated balance, if `≤ floor` call `streamingService.autoTerminate(streamId)`.

---

### `engine-spring/.../scheduler/AuditArchivalJob.java` (service, batch)

**Analog:** None — use RESEARCH.md Pattern 4 (ShedLock) directly.

Class-level: `@Component @RequiredArgsConstructor @Slf4j`. Inject `JdbcTemplate jdbcTemplate` + `TokenEngineProperties properties`.

```java
@Scheduled(cron = "${token-engine.audit.cron:0 0 2 * * *}")
@SchedulerLock(
    name = "audit_archival_job",
    lockAtMostFor = "${token-engine.audit.lock-at-most-hours:PT2H}",
    lockAtLeastFor = "${token-engine.audit.lock-at-least-minutes:PT1M}"
)
public void runArchival() {
    LockAssert.assertLocked();
    // 1. INSERT INTO audit_archive.balance_audit_log SELECT ... WHERE recorded_at < cutoff
    // 2. DELETE FROM balance_audit_log WHERE recorded_at < cutoff
    // 3. DELETE FROM idempotency_keys WHERE created_at < now() - interval
}
```

---

### `engine-spring/.../config/TokenEngineProperties.java` (MODIFY, config, request-response)

**Analog:** `engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java`

**Current full file** (lines 1-15):
```java
@ConfigurationProperties(prefix = "token-engine")
@Getter
@Setter
public class TokenEngineProperties {

    private BigDecimal balanceFloor = BigDecimal.ZERO;
}
```

Add three nested static classes following `@Getter @Setter` pattern (consistent with the existing `@Getter @Setter` class-level annotations). Each nested class uses `@Getter @Setter` on itself:

```java
@Getter
@Setter
public static class StreamingProperties {
    private long fallbackSweepSeconds = 300;
}

@Getter
@Setter
public static class AuditProperties {
    private int retentionDays = 90;
    private String cron = "0 0 2 * * *";
    private String lockAtMostHours = "PT2H";
    private String lockAtLeastMinutes = "PT1M";
}

@Getter
@Setter
public static class RedisProperties {
    private String sentinelMaster;
    private String sentinelNodes;
}
```

Add fields: `private StreamingProperties streaming = new StreamingProperties();`, `private AuditProperties audit = new AuditProperties();`, `private RedisProperties redis = new RedisProperties();`

---

### `engine-spring/.../autoconfigure/TokenEngineAutoConfiguration.java` (MODIFY, config, request-response)

**Analog:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java`

**Current bean registration pattern** (lines 27-50):
```java
@Bean
@ConditionalOnMissingBean
public AccountService accountService(
        AccountRepository accountRepository,
        ...) {
    return new AccountServiceImpl(...);
}
```

For Phase 3: add `StreamingAutoConfiguration` as a separate `@AutoConfiguration` class (per RESEARCH.md open question 3 recommendation). The existing `TokenEngineAutoConfiguration` is modified only to register `LockProvider` bean.

In a new `StreamingAutoConfiguration`:
- `@Bean @ConditionalOnMissingBean` for `StreamRegistry` (returns `RedisStreamRegistry`)
- `@Bean @ConditionalOnMissingBean` for `StreamingService` (returns `StreamingServiceImpl`)
- `@Bean @ConditionalOnMissingBean` for `AutoTerminationScheduler`
- `@Bean @ConditionalOnMissingBean` for `FallbackSweepJob`
- `@Bean @ConditionalOnMissingBean` for `AuditArchivalJob`
- `@Bean @ConditionalOnProperty("token-engine.redis.sentinel.master")` for sentinel `RedisConnectionFactory`

`LockProvider` bean belongs in the existing `TokenEngineAutoConfiguration` (or a dedicated `SchedulerConfiguration` that is `@Import`ed) alongside `@EnableScheduling` and `@EnableSchedulerLock`.

---

### `engine-service/.../controller/StreamController.java` (controller, request-response)

**Analog:** `engine-service/src/main/java/com/certacota/engine/service/controller/AccountController.java`

**Full class pattern** (lines 1-61):
```java
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;
    private final TransactionService transactionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    @PostMapping("/{accountId}/credit")
    @ResponseStatus(HttpStatus.CREATED)
    public PostTransactionResponse credit(
            @PathVariable String accountId,
            @Valid @RequestBody CreditRequest request) {
        log.info("Posting CREDIT of {} to account: {}", request.amount(), accountId);
        return transactionService.credit(accountId, request);
    }
}
```

For `StreamController`:
- `@RequestMapping("/api/v1/streams")`
- `POST /` → `@ResponseStatus(HttpStatus.CREATED)` → `streamingService.startStream(request)`
- `POST /{streamId}/stop` → `@ResponseStatus(HttpStatus.OK)` (default) → `streamingService.stopStream(streamId, request)`
- Thin controller: log the inbound call, delegate immediately, no business logic

For `EstimationController`:
- `@RequestMapping("/api/v1/accounts")`
- `GET /{accountId}/estimated-balance` → `@Transactional(readOnly = true)` on the service, NOT on the controller method → `streamingService.estimateBalance(accountId)`

---

### `engine-service/.../controller/GlobalExceptionHandler.java` (MODIFY, middleware, request-response)

**Analog:** `engine-service/src/main/java/com/certacota/engine/service/controller/GlobalExceptionHandler.java`

**Existing handler pattern** (lines 19-26):
```java
@ExceptionHandler(AccountNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public Map<String, String> handleNotFound(AccountNotFoundException ex) {
    log.warn("Account not found: {}", ex.getMessage());
    return Map.of("error", ex.getMessage());
}
```

Add two new handlers following the exact same pattern:
```java
@ExceptionHandler(StreamNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public Map<String, String> handleStreamNotFound(StreamNotFoundException ex) {
    log.warn("Stream not found: {}", ex.getMessage());
    return Map.of("error", ex.getMessage());
}

@ExceptionHandler(RedisUnavailableException.class)
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public Map<String, String> handleRedisUnavailable(RedisUnavailableException ex) {
    log.warn("Redis unavailable: {}", ex.getMessage());
    return Map.of("error", ex.getMessage());
}
```

---

### `engine-service/src/test/.../TestcontainersConfiguration.java` (MODIFY, config, batch)

**Analog:** `engine-service/src/test/java/com/certacota/engine/service/TestcontainersConfiguration.java`

**Current full file** (lines 1-17):
```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }
}
```

Add a Redis container alongside the existing Postgres container. `@ServiceConnection` is NOT available for `GenericContainer` — use `@DynamicPropertySource` instead (per RESEARCH.md Pattern 7 and assumption A5):

```java
@Bean
GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);
}

@DynamicPropertySource
static void redisProperties(DynamicPropertyRegistry registry) {
    // Cannot be static and reference @Bean — requires companion class or ApplicationContextInitializer
}
```

Note: `@DynamicPropertySource` is a static method and cannot reference the `@Bean` container instance directly when the container is declared as a `@Bean` method. The clean solution: declare the container as a static field instead of a `@Bean`, or use a companion `@TestConfiguration` class. Recommended approach: declare `static GenericContainer<?> redis = new GenericContainer<>(...).withExposedPorts(6379)` as a static field so `@DynamicPropertySource` can reference it. This deviates from the Postgres `@Bean @ServiceConnection` pattern but is the established Testcontainers pattern for `GenericContainer`.

---

### `engine-service/src/test/.../steps/StreamingSteps.java` (test, request-response)

**Analog:** `engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java`

**Class-level structure** (lines 34-62):
```java
@Slf4j
public class TransactionSteps {

    @Autowired private AccountRepository accountRepository;
    @Autowired private DiscreteTransactionRepository discreteTransactionRepository;
    @Autowired private BalanceAuditLogRepository auditLogRepository;
    @Autowired private SharedContext sharedContext;

    @LocalServerPort private int port;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TransactionSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }
```

Copy the exact `RestTemplate` setup with `DefaultResponseErrorHandler` override — this is required so that `4xx`/`5xx` responses do not throw exceptions and can be asserted in `Then` steps. `ObjectMapper` instantiated inline (not injected) follows the established pattern.

For `StreamingSteps` — inject `StreamingTransactionRepository` additionally. Add `@Autowired private StreamingTransactionRepository streamingTransactionRepository`.

HTTP call pattern to copy:
```java
@When("I start a stream {string} on account {string} at rate {bigdecimal} with idempotency key {string}")
public void startStream(String streamId, String accountId, BigDecimal rate, String idempotencyKey) {
    String url = "http://localhost:" + port + "/api/v1/streams";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> body = new HashMap<>();
    body.put("streamId", streamId);
    body.put("accountId", accountId);
    body.put("ratePerSecond", rate);
    body.put("idempotencyKey", idempotencyKey);
    HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
    ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
    sharedContext.setLastResponse(response);
    log.info("Start stream response: {} {}", response.getStatusCode(), response.getBody());
}
```

---

### `engine-service/src/test/.../StreamingConcurrencyTest.java` (test, request-response)

**Analog:** `engine-service/src/test/java/com/certacota/engine/service/DiscreteTransactionConcurrencyTest.java`

**Full class structure** (lines 30-120):
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DiscreteTransactionConcurrencyTest {

    @LocalServerPort private int port;
    @Autowired private AccountRepository accountRepository;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) { return false; }
        });
    }

    @Test
    void concurrentDebitsDoNotDoubleSpend() throws InterruptedException {
        // 1. Setup account via REST
        // 2. Launch N threads with CountDownLatch barrier
        // 3. Count 201/non-201 responses with AtomicInteger
        // 4. Assert final committed balance == initial - (successCount × amount)
    }
}
```

For `StreamingConcurrencyTest`: same class-level annotations. Test scenario: start one stream + N concurrent discrete debits simultaneously, assert no double-spend and no balance below floor. Uses `ExecutorService`, `CountDownLatch`, `AtomicInteger` — copy the concurrency harness verbatim from lines 65-103, change only the URLs and business assertions.

---

### Feature files: `streaming-*.feature` (6 files, test, request-response)

**Analog:** `engine-service/src/test/resources/features/discrete-credit.feature`

**Full feature pattern** (lines 1-25):
```gherkin
Feature: Discrete credit transactions

  Scenario: Credit increases account balance
    Given no account with id "credit-001" exists
    And an account "credit-001" exists with balance 100.00
    When I post a CREDIT of 50.00 to account "credit-001" with idempotency key "credit-key-001"
    Then the response status is 201
    And the transaction response has balanceAfter 150.00
    And account "credit-001" has committed balance 150.00
```

Conventions to copy:
- `Feature:` name matches the file name topic
- `Scenario:` names are plain English describing the behavior
- `Given` sets up state (account creation, existing streams)
- `When` performs the action (REST call)
- `Then` asserts the outcome (response status, balance, audit log entry)
- Account IDs are unique per scenario (e.g., `"stream-start-001"`, `"stream-stop-001"`) to avoid cross-scenario contamination
- `Given no account with id "..." exists` step (already implemented in `AccountSteps`) called at start of every scenario that creates a new account
- Numeric literals use exact decimal format matching BigDecimal assertions (e.g., `100.000000000000000000` is acceptable but `100.00` is conventional)

---

## Shared Patterns

### Pessimistic Write Lock (findWithLock)

**Source:** `engine-core/src/main/java/com/certacota/engine/core/repository/AccountRepository.java` lines 14-16
**Apply to:** All streaming write paths (startStream, stopStream, autoTerminate, debit modification, account close modification)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findWithLock(@Param("id") String id);
```

The lock must be acquired BEFORE any balance check, StreamRegistry read, or idempotency check — identical to Phase 2 constraint. Lock ordering for streams: always acquire account lock before reading StreamRegistry.

### @Transactional + @Slf4j + @RequiredArgsConstructor

**Source:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java` lines 32-35
**Apply to:** All service implementation classes (`StreamingServiceImpl`, `FallbackSweepJob`, `AuditArchivalJob`)

```java
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {
```

`@Transactional` at class level covers all methods. Override with `@Transactional(readOnly = true)` on read-only methods like `estimateBalance`.

### Audit Log Write

**Source:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java` lines 77-86
**Apply to:** `StreamingServiceImpl.startStream()` (operation: `"STREAMING_START"`) and `stopStream()` / `autoTerminate()` (operation: `"STREAMING_SETTLE"`)

```java
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
```

For streaming: `transactionId` carries the `StreamingTransaction.getId()`. The `reason` field is not a column in `BalanceAuditLog` — carry it in the `operation` field (e.g., `"STREAMING_SETTLE"`) or add a `reason` field if the entity is extended in Phase 3.

### Idempotency Check After Lock

**Source:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java` lines 99-104
**Apply to:** `StreamingServiceImpl.startStream()` only (stop is not idempotent — duplicate stop returns 404)

```java
var existing = idempotencyKeyRepository
    .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_DEBIT");
if (existing.isPresent()) {
    log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
    return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
}
```

### RoundingMode.DOWN + setScale(18)

**Source:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java` line 185
**Apply to:** All BigDecimal arithmetic in `StreamingServiceImpl` (rate × elapsed, increment billing, clamp calculation)

```java
BigDecimal rakeAmount = request.amount().multiply(rakeRate).setScale(18, RoundingMode.DOWN);
```

Every intermediate result must call `.setScale(18, RoundingMode.DOWN)`. Final settled amounts stored to Postgres must also be `NUMERIC(38,18)`.

### Exception Pattern (runtime, single-message constructor)

**Source:** `engine-core/src/main/java/com/certacota/engine/core/exception/AccountNotFoundException.java` lines 1-7
**Apply to:** `StreamNotFoundException`, `RedisUnavailableException`, `StreamAlreadyActiveException` (for duplicate streamId)

```java
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
```

### RestTemplate Non-Throwing Error Handler (tests)

**Source:** `engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java` lines 55-61
**Apply to:** `StreamingSteps` constructor, `StreamingConcurrencyTest.setUp()`

```java
restTemplate = new RestTemplate();
restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
    @Override
    public boolean hasError(ClientHttpResponse response) {
        return false;
    }
});
```

Without this override, `restTemplate.exchange()` throws on 4xx/5xx, preventing `Then` steps from asserting error responses.

### GlobalExceptionHandler Response Shape

**Source:** `engine-service/src/main/java/com/certacota/engine/service/controller/GlobalExceptionHandler.java` lines 19-25
**Apply to:** All new `@ExceptionHandler` methods

```java
@ExceptionHandler(AccountNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public Map<String, String> handleNotFound(AccountNotFoundException ex) {
    log.warn("Account not found: {}", ex.getMessage());
    return Map.of("error", ex.getMessage());
}
```

Single `Map<String, String>` with key `"error"` and message from `ex.getMessage()`. `log.warn` for expected exceptions. This is what the Cucumber steps parse when asserting error messages.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `engine-spring/.../redis/RedisStreamRegistry.java` | service | event-driven | No Redis components exist in the codebase; use RESEARCH.md Pattern 1 |
| `engine-spring/.../scheduler/AutoTerminationScheduler.java` | service | event-driven | No Redisson/scheduler components exist; use RESEARCH.md Pattern 3 |
| `engine-spring/.../scheduler/FallbackSweepJob.java` | service | batch | No `@Scheduled` + ShedLock components exist; use RESEARCH.md Pattern 4 |
| `engine-spring/.../scheduler/AuditArchivalJob.java` | service | batch | No scheduled archival exists; use RESEARCH.md Pattern 4 + RESEARCH.md DDL §V9 |

---

## Metadata

**Analog search scope:** All Java files in engine-core, engine-spring, engine-service (27 source files + 15 test files + 9 feature files + 6 migration files)
**Files scanned:** 27 Java source + test files read in full; all migration SQL files read
**Pattern extraction date:** 2026-05-14
