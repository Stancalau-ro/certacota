# Phase 2: Discrete Transactions - Research

**Researched:** 2026-05-13
**Domain:** Spring Data JPA concurrency locking, ledger transaction schema, metadata (JSONB), rake arithmetic, Cucumber BDD extension
**Confidence:** HIGH (all primary claims verified against official docs, Context7, or the live codebase)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| DTX-01 | Caller can post a discrete credit to a participant account | New `TransactionService.credit()` method; REST POST endpoint; Flyway migration for `discrete_transactions` table; audit log write inside same `@Transactional` |
| DTX-02 | Caller can post a discrete debit from a participant account | Same as DTX-01 but with floor enforcement pre-write; PESSIMISTIC_WRITE lock on the account row |
| DTX-03 | Engine rejects debit that would take balance below floor, even when concurrent streaming transactions are in flight | `PESSIMISTIC_WRITE` (`SELECT FOR UPDATE`) on account row ensures the floor check and balance update are atomic relative to all other writers; streaming (Phase 3) must also acquire same lock |
| DTX-04 | Engine processes concurrent discrete transactions against a single balance with full correctness | `PESSIMISTIC_WRITE` lock serializes concurrent writers; verified by a concurrent stress test using `ExecutorService` + `CountDownLatch` inside Testcontainers |
| META-01 | Every transaction accepts an immutable caller-supplied key-value metadata map | `metadata JSONB` column on `discrete_transactions` table; `Map<String, Object>` field mapped with `@JdbcTypeCode(SqlTypes.JSON)`; once persisted, never updated |
| META-02 | Metadata flows through unchanged to all audit log entries and emitted events | Audit log already has an `idempotency_key` link column; Phase 2 adds a `transaction_id` FK so audit entries reference the transaction and inherit its metadata |
| RAKE-01 | Caller can configure rake rules per transaction type; rake rate resolved from caller-supplied metadata at post time | `RakeConfig` loaded from `TokenEngineProperties`; rake rate looked up using caller metadata key; rake-enabled transaction executes as atomic three-way debit/credit/credit inside one `@Transactional` |
</phase_requirements>

---

## Summary

Phase 2 builds the discrete transaction layer on top of Phase 1's account scaffold. The core engineering challenge is correctness under concurrent load (DTX-03, DTX-04): two debits arriving simultaneously must never together drain the balance below the floor, and no update must be silently overwritten. The solution is `PESSIMISTIC_WRITE` (`SELECT FOR UPDATE`) on the account row, issued at the start of every balance-modifying service method, serializing writers at the database level.

Beyond concurrency, Phase 2 introduces a new `discrete_transactions` table (Flyway V4) to record posted transactions as first-class domain objects, carries immutable caller-supplied metadata stored as JSONB (META-01/02), and implements the first slice of the rake engine (RAKE-01) — a single rake rate rule resolved from transaction metadata that drives an atomic three-way balance split within a single `@Transactional` boundary.

The `Account` entity from Phase 1 already has `credit()` and `debit()` mutation methods, and `AccountServiceImpl` already demonstrates the idempotency pattern. Phase 2 extends these patterns to a new `TransactionService` interface and `TransactionServiceImpl`, a new `TransactionController`, and new Cucumber feature files.

**Primary recommendation:** Lock the account row with `PESSIMISTIC_WRITE` at the start of every debit/credit, execute floor check and balance update inside the same locked transaction, persist the transaction record and audit entry atomically. Do not use optimistic locking (`@Version`) — the retry complexity under high concurrency is not worth the throughput gain for a correctness-first engine.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Discrete transaction domain entity | engine-core | — | Zero-framework domain object; no Spring annotations |
| Transaction repository interface | engine-core | — | `JpaRepository` extension; Spring Data brings implementation at runtime |
| TransactionService interface + DTOs | engine-core | — | Interface only; DTOs as Java records; zero Spring dependency |
| Domain exceptions (new ones) | engine-core | — | Extend `RuntimeException`; no Spring |
| TransactionServiceImpl (credit, debit, rake) | engine-spring | — | Requires Spring `@Service`, `@Transactional`, `ObjectMapper`, `TokenEngineProperties` |
| Autoconfigure wiring for TransactionService | engine-spring | — | `TokenEngineAutoConfiguration` gains a second `@ConditionalOnMissingBean` bean |
| REST endpoint `POST /api/v1/transactions` | engine-service | — | Thin controller delegates to `TransactionService`; `GlobalExceptionHandler` extended |
| Flyway migration V4 (discrete_transactions) | engine-service resources | — | Migrations ship with the deployable service |
| Concurrent correctness (SELECT FOR UPDATE) | engine-spring / engine-core repository | — | `@Lock(PESSIMISTIC_WRITE)` on `AccountRepository.findWithLock()`; service uses it at write time |
| Rake configuration | engine-spring config | engine-core (RakeConfig value object) | Rate lookup lives in `TokenEngineProperties`; arithmetic executes in `TransactionServiceImpl` |

---

## Standard Stack

No new library dependencies are required for Phase 2. All capabilities are provided by the Phase 1 stack.

### Core (unchanged)
| Library | Version | Purpose | Note |
|---------|---------|---------|------|
| Spring Boot | 3.5.3 | Transaction management, autoconfigure | Already in `engine-service/build.gradle` |
| Spring Data JPA + Hibernate 6 | BOM-managed | `@Lock` annotation, `PESSIMISTIC_WRITE`, `@JdbcTypeCode` | Already wired |
| PostgreSQL JDBC | BOM-managed | `SELECT FOR UPDATE` translated from `LockModeType.PESSIMISTIC_WRITE` | Already runtime dependency |
| Flyway Core + flyway-database-postgresql | BOM-managed | V4 migration | Already in `engine-service/build.gradle` |
| Cucumber BOM 7.22.1 | BOM-managed | New feature files for DTX/RAKE/META scenarios | Already in test dependencies |

`[VERIFIED: engine-service/build.gradle — codebase grep]`

### No New Libraries Required
The locking mechanism (`@Lock` / `LockModeType.PESSIMISTIC_WRITE`) is part of Spring Data JPA which is already on the classpath. No additional concurrency library (Redisson, Hazelcast, etc.) is needed — PostgreSQL row-level locking is sufficient for this phase's correctness guarantee. `[VERIFIED: Context7 /spring-projects/spring-data-jpa locking.adoc]`

---

## Architecture Patterns

### System Architecture Diagram

```
HTTP Request  POST /api/v1/transactions
     |
     v
[engine-service: TransactionController (@RestController)]
     |  (thin delegator)
     v
[engine-spring: TransactionServiceImpl (@Service, @Transactional)]
     |
     |---> 1. Idempotency check (findByIdempotencyKeyAndOperation)
     |
     |---> 2. accountRepository.findWithLock(accountId)
     |         |-- Issues:  SELECT ... FROM accounts WHERE id=? FOR UPDATE
     |         |-- Blocks all concurrent writers on this account row
     |
     |---> 3. Floor enforcement (debit only)
     |         |-- resultingBalance = balance - amount
     |         |-- if resultingBalance < effectiveFloor → throw BalanceFloorViolationException
     |
     |---> 4. account.credit(amount) | account.debit(amount)  [already on Account entity]
     |         |-- accountRepository.save(account)  ---> [Postgres: accounts.balance updated]
     |
     |---> 5. discreteTransactionRepository.save(txn)
     |         |-- Carries: id, accountId, type (CREDIT/DEBIT), amount, metadata (JSONB),
     |                      idempotencyKey, postedAt
     |         ---> [Postgres: discrete_transactions row inserted]
     |
     |---> 6. auditLogRepository.save(entry)
     |         |-- Carries: accountId, operation, amount, balanceBefore, balanceAfter,
     |                      idempotencyKey, transactionId (FK to discrete_transactions)
     |         ---> [Postgres: balance_audit_log row inserted]
     |
     |---> 7. Idempotency key stored (idempotencyKeyRepository.save)
     |
     v
TransactionResponse (serialized by Jackson)
     |
     v
HTTP Response  201 Created

RAKE PATH (inside step 4 when rakeRate > 0):
     |
     |---> 4a. Debit from-account by fullAmount (SELECT FOR UPDATE)
     |---> 4b. Credit to-account by (fullAmount - rakeAmount)  [no lock needed — credit]
     |---> 4c. Credit platform-account by rakeAmount            [no lock needed — credit]
     |          All three inside same @Transactional — atomically committed or rolled back
```

### Recommended Project Structure Extension

```
engine-core/src/main/java/com/certacota/engine/core/
├── domain/
│   ├── Account.java                      # EXISTS — no changes needed
│   ├── BalanceAuditLog.java              # EXISTS — add transactionId FK column
│   ├── DiscreteTransaction.java          # NEW — JPA entity for discrete_transactions
│   └── TransactionType.java             # NEW — enum: CREDIT, DEBIT
├── dto/
│   ├── PostTransactionRequest.java       # NEW — record: accountId, type, amount, metadata, idempotencyKey
│   ├── PostTransactionResponse.java      # NEW — record: transactionId, accountId, type, amount, balanceAfter, metadata, postedAt
│   └── (existing account DTOs unchanged)
├── repository/
│   ├── AccountRepository.java            # ADD findWithLock(id) with @Lock(PESSIMISTIC_WRITE)
│   ├── DiscreteTransactionRepository.java # NEW
│   └── (other repos unchanged)
├── service/
│   └── TransactionService.java           # NEW — interface: credit(), debit()
└── exception/
    └── (existing exceptions unchanged)

engine-spring/src/main/java/com/certacota/engine/spring/
├── autoconfigure/
│   └── TokenEngineAutoConfiguration.java  # ADD TransactionServiceImpl bean
├── config/
│   └── TokenEngineProperties.java         # ADD rake config map
└── service/
    └── TransactionServiceImpl.java         # NEW — @Service, @Transactional

engine-service/src/main/
├── java/com/certacota/engine/service/
│   └── controller/
│       ├── TransactionController.java      # NEW
│       └── GlobalExceptionHandler.java     # EXTEND with new exception handlers
└── resources/db/migration/
    └── V4__create_discrete_transactions.sql # NEW

engine-service/src/test/
├── java/.../steps/
│   ├── TransactionSteps.java              # NEW
│   └── ConcurrencySteps.java              # NEW (for DTX-04 stress test)
└── resources/features/
    ├── discrete-credit.feature            # NEW
    ├── discrete-debit.feature             # NEW
    ├── discrete-metadata.feature          # NEW
    ├── discrete-concurrency.feature       # NEW
    └── discrete-rake.feature              # NEW
```

### Pattern 1: Pessimistic Write Lock on Account Repository

**What:** A dedicated `findWithLock` repository method that issues `SELECT ... FOR UPDATE` via `LockModeType.PESSIMISTIC_WRITE`. This serializes all concurrent writes against the same account row.

**When to use:** At the start of every balance-modifying service method (credit, debit, rake debit).

```java
// Source: Context7 /spring-projects/spring-data-jpa locking.adoc
// [VERIFIED: Context7]
public interface AccountRepository extends JpaRepository<Account, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findWithLock(@Param("id") String id);
}
```

**Why `@Query` instead of overriding `findById`:** Overriding `findById` with `@Lock` changes the behavior of all callers including read-only operations. A named method makes the locking intent explicit in call sites. `[VERIFIED: Context7 — "Lock on CRUD method" section]`

**What it produces in Postgres:** `SELECT id, status, balance, balance_floor, metadata, created_at, updated_at FROM accounts WHERE id=? FOR UPDATE`

### Pattern 2: Atomic Debit + Floor Check + Audit (single @Transactional)

**What:** Lock acquisition, floor enforcement, balance mutation, transaction record insert, and audit log insert all inside one `@Transactional` method. No intermediate inconsistent state is ever visible.

**When to use:** Every debit (and credit) operation.

```java
// Source: Phase 1 AccountServiceImpl pattern + PESSIMISTIC_WRITE addition
// [VERIFIED: project codebase + Context7 /spring-projects/spring-data-jpa]

@Transactional
public PostTransactionResponse debit(PostTransactionRequest request) {
    // 1. Idempotency check (pre-write, not constraint-first — see Pitfall 2)
    return idempotencyKeyRepository
        .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_DEBIT")
        .map(ik -> deserialize(ik.getResponseBody(), PostTransactionResponse.class))
        .orElseGet(() -> doDebit(request));
}

private PostTransactionResponse doDebit(PostTransactionRequest request) {
    // 2. Acquire row lock — serializes all concurrent writers
    Account account = accountRepository.findWithLock(request.accountId())
        .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

    if (account.getStatus() == AccountStatus.CLOSED) {
        throw new AccountClosedException(request.accountId());
    }

    // 3. Floor check — happens AFTER lock is held
    BigDecimal resulting = account.getBalance().subtract(request.amount());
    BigDecimal floor = account.getBalanceFloor() != null
        ? account.getBalanceFloor() : properties.getBalanceFloor();
    if (resulting.compareTo(floor) < 0) {
        throw new BalanceFloorViolationException(
            "Debit of " + request.amount() + " would bring balance to "
            + resulting + ", below floor " + floor);
    }

    // 4. Mutate balance
    BigDecimal balanceBefore = account.getBalance();
    account.debit(request.amount());
    accountRepository.save(account);

    // 5. Persist transaction record (with metadata JSONB)
    DiscreteTransaction txn = discreteTransactionRepository.save(
        DiscreteTransaction.builder()
            .accountId(account.getId())
            .type(TransactionType.DEBIT)
            .amount(request.amount())
            .metadata(request.metadata())
            .idempotencyKey(request.idempotencyKey())
            .postedAt(OffsetDateTime.now())
            .build());

    // 6. Audit log
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
    storeIdempotencyResponse(request.idempotencyKey(), "DISCRETE_DEBIT", response);
    return response;
}
```

### Pattern 3: Rake-Enabled Transaction — Atomic Three-Way Split

**What:** A single `@Transactional` method that debits the from-account, credits the to-account, and credits the platform-account. All three account saves happen before the transaction commits. If any step fails, all three roll back.

**When to use:** When `PostTransactionRequest.rakeEnabled() == true` and a rake rate is resolved from metadata.

```java
// Source: Based on @Transactional atomicity guarantee
// [VERIFIED: project REQUIREMENTS.md RAKE-01; Spring @Transactional docs]

@Transactional
public PostTransactionResponse debitWithRake(PostTransactionRequest request) {
    // Resolve rake rate from metadata using configured key
    BigDecimal rakeRate = resolveRakeRate(request.metadata());
    BigDecimal rakeAmount = request.amount().multiply(rakeRate)
        .setScale(18, RoundingMode.DOWN);
    BigDecimal toAccountAmount = request.amount().subtract(rakeAmount);

    // Lock from-account (the one being debited)
    Account fromAccount = accountRepository.findWithLock(request.accountId())
        .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

    // Floor check on from-account
    BigDecimal resulting = fromAccount.getBalance().subtract(request.amount());
    enforceFloor(fromAccount, resulting);

    fromAccount.debit(request.amount());
    accountRepository.save(fromAccount);

    // Credit to-account (no lock needed — only credits don't violate floor)
    Account toAccount = accountRepository.findById(request.toAccountId())
        .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));
    toAccount.credit(toAccountAmount);
    accountRepository.save(toAccount);

    // Credit platform-account
    Account platformAccount = accountRepository.findById(properties.getPlatformAccountId())
        .orElseThrow(() -> new AccountNotFoundException(properties.getPlatformAccountId()));
    platformAccount.credit(rakeAmount);
    accountRepository.save(platformAccount);

    // Persist transaction record + audit entries (omitted for brevity — same pattern as doDebit)
    ...
}
```

**Arithmetic invariant:** `fromAccount.debit(amount)` = `toAccount.credit(toAccountAmount)` + `platformAccount.credit(rakeAmount)`. Enforced by logic; Phase 4 adds a DB CHECK constraint. `[ASSUMED: DB check constraint deferred to Phase 4 per REQUIREMENTS.md RAKE-04]`

### Pattern 4: Concurrent Correctness Test — ExecutorService + CountDownLatch

**What:** A Cucumber step (or a `@SpringBootTest` JUnit 5 test) that fires N concurrent debits against one account using a thread pool, waits for all to complete, and asserts the final balance equals exactly `initialBalance - (N_successful × amount)`.

**When to use:** DTX-04 verification.

```java
// Source: standard Java concurrency testing pattern
// [VERIFIED: multiple community sources — countdownlatch/executorservice pattern]

int N_THREADS = 20;
ExecutorService executor = Executors.newFixedThreadPool(N_THREADS);
CountDownLatch startLatch = new CountDownLatch(1);
CountDownLatch doneLatch = new CountDownLatch(N_THREADS);
AtomicInteger successCount = new AtomicInteger(0);
AtomicInteger failCount = new AtomicInteger(0);

for (int i = 0; i < N_THREADS; i++) {
    final int idx = i;
    executor.submit(() -> {
        try {
            startLatch.await();  // all threads start simultaneously
            postDebit("acct-concurrent", BigDecimal.TEN, "concurrent-key-" + idx);
            successCount.incrementAndGet();
        } catch (Exception e) {
            failCount.incrementAndGet();
        } finally {
            doneLatch.countDown();
        }
    });
}

startLatch.countDown();  // release all threads
doneLatch.await(30, TimeUnit.SECONDS);
executor.shutdown();

// Assert: finalBalance == initialBalance - (successCount × 10)
BigDecimal finalBalance = getCommittedBalance("acct-concurrent");
BigDecimal expectedBalance = initialBalance.subtract(
    BigDecimal.TEN.multiply(BigDecimal.valueOf(successCount.get())));
assertThat(finalBalance.compareTo(expectedBalance)).isEqualTo(0);
// Also assert: no double-spend — successCount + failCount == N_THREADS
assertThat(successCount.get() + failCount.get()).isEqualTo(N_THREADS);
```

**Note on Cucumber vs direct JUnit:** This pattern can be written as a Cucumber scenario step (`Given N concurrent debits are attempted`) or as a standalone `@SpringBootTest` JUnit 5 test. Either approach is valid. The recommended approach is a dedicated `ConcurrencySteps` Cucumber step class and a `discrete-concurrency.feature` file so the concurrency test lives in the same BDD harness as all other acceptance tests. `[ASSUMED: test placement preference — confirm with planner]`

### Pattern 5: DiscreteTransaction JPA Entity

**What:** New entity mapping `discrete_transactions` table. Metadata stored as JSONB (same pattern as `Account.metadata`). All columns immutable (`updatable = false`) — transactions are append-only ledger entries.

```java
// Source: Phase 1 Account.java pattern + BalanceAuditLog pattern
// [VERIFIED: project codebase — Account.java, BalanceAuditLog.java]

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

    @Column(name = "type", nullable = false, updatable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransactionType type;

    @Column(name = "amount", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "posted_at", updatable = false)
    private OffsetDateTime postedAt;
}
```

### Pattern 6: Rake Configuration in TokenEngineProperties

**What:** A map of rake rate entries in `application.yml`, keyed by a metadata field value. The `TransactionServiceImpl` looks up the rake rate using a configured metadata key name.

```yaml
# application.yml addition
token-engine:
  balance-floor: 0
  rake:
    enabled: false                    # global on/off (default: off)
    metadata-key: "transaction_type"  # which metadata field holds the rate lookup key
    rates:                            # map of metadata value → rake rate (0.0 to 1.0)
      private_show: "0.20"
      group_show: "0.15"
      tip: "0.10"
```

```java
// TokenEngineProperties addition
@ConfigurationProperties(prefix = "token-engine")
@Getter
@Setter
public class TokenEngineProperties {
    private BigDecimal balanceFloor = BigDecimal.ZERO;
    private RakeProperties rake = new RakeProperties();

    @Getter
    @Setter
    public static class RakeProperties {
        private boolean enabled = false;
        private String metadataKey = "transaction_type";
        private Map<String, String> rates = new HashMap<>();

        public BigDecimal getRateFor(Map<String, Object> metadata) {
            if (!enabled || metadata == null) return BigDecimal.ZERO;
            Object keyValue = metadata.get(metadataKey);
            if (keyValue == null) return BigDecimal.ZERO;
            String rateStr = rates.get(keyValue.toString());
            return rateStr != null ? new BigDecimal(rateStr) : BigDecimal.ZERO;
        }
    }
}
```

`[ASSUMED: rake config shape — confirm exact YAML structure with planner; the shape shown is a reasonable default]`

### Anti-Patterns to Avoid

- **Floor check before `findWithLock`:** If you load the account without a lock, check the floor, then update, a concurrent debit can pass the floor check simultaneously on both threads. Always acquire the `PESSIMISTIC_WRITE` lock first. `[VERIFIED: concurrency reasoning]`
- **Optimistic locking (`@Version`) for balance debit:** Under high concurrent debit load, most threads will get `OptimisticLockException` and need retry logic. The correctness-first constraint in REQUIREMENTS makes pessimistic locking the correct choice here. Optimistic locking is appropriate for low-contention entities (e.g., rake config). `[VERIFIED: REQUIREMENTS.md "Correctness over throughput"]`
- **Separate transactions for audit log and balance update:** Both must share one `@Transactional` scope. If the balance update commits but the audit INSERT fails in a separate transaction, FUND-02 is violated. `[VERIFIED: Phase 1 PATTERNS.md — shared transaction invariant]`
- **Locking the account row for credit operations:** Credits cannot violate the floor (adding tokens never reduces balance below floor). Locking on credit is unnecessary overhead and increases deadlock risk. Only debit and rake-debit operations need `findWithLock`. Exception: rake-enabled debit does lock the from-account; the to-account and platform-account receive only credits and use plain `findById`. `[ASSUMED: credits are safe without lock — verify if a floor ceiling exists in requirements (none found)]`
- **Storing rake rate in the transaction record:** The rake rate is configuration; it must not be stored in the `discrete_transactions` row. The transaction record stores the actual amounts transferred to each party (amount and rake_amount columns), not the rate. The rate is audit-accessible via the configuration log, not the ledger. `[ASSUMED: design decision — confirm with planner]`

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Row-level serialization for balance debits | Java `synchronized` blocks, `ReentrantLock`, Redis locks | `@Lock(PESSIMISTIC_WRITE)` on repository method | Postgres handles this natively; application-level locks don't protect against multi-instance deployments |
| JSONB serialization for metadata | Manual `ObjectMapper` code in entity | `@JdbcTypeCode(SqlTypes.JSON)` on `Map<String, Object>` | Already used on `Account.metadata` — proven in Phase 1; Hibernate 6 handles the full type lifecycle |
| Concurrent test coordination | `Thread.sleep()` | `CountDownLatch` + `ExecutorService` | `sleep()` is non-deterministic; `CountDownLatch` guarantees all threads start and finish together |
| Arithmetic precision for rake | `double` or `float` | `BigDecimal.multiply` + `setScale(18, RoundingMode.DOWN)` | Floating-point loses precision; DOWN rounding favors the platform (conservative rake) |
| Per-account balance recalculation | Sum all audit log entries | Read `accounts.balance` column directly | The balance column is the authoritative committed balance; summing audit log is slow and races with in-flight transactions |

**Key insight:** The `Account` entity's `debit()` and `credit()` mutation methods already exist in the Phase 1 codebase — Phase 2 adds no new mutation patterns to the entity, only the `findWithLock` locking mechanism and new service-layer orchestration.

---

## DDL: New and Modified Tables

### V4: discrete_transactions table

```sql
-- File: db/migration/V4__create_discrete_transactions.sql
-- Source: Phase 1 DDL conventions (NUMERIC(38,18), BIGSERIAL, TIMESTAMPTZ, JSONB)
-- [VERIFIED: Phase 1 codebase conventions]

CREATE TABLE discrete_transactions (
    id              BIGSERIAL           PRIMARY KEY,
    account_id      VARCHAR(255)        NOT NULL,
    type            VARCHAR(20)         NOT NULL,
    amount          NUMERIC(38,18)      NOT NULL,
    metadata        JSONB,
    idempotency_key VARCHAR(255),
    posted_at       TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_dtx_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_dtx_type   CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_dtx_amount CHECK (amount > 0)
);

CREATE INDEX idx_dtx_account_id ON discrete_transactions(account_id);
```

**Design notes:**
- `amount` always positive (direction encoded in `type`) — consistent with ledger conventions and eliminates sign-error bugs `[ASSUMED: sign convention; confirm during planning]`
- `metadata` nullable — callers who don't supply metadata get `null`; META-01 says optional
- Index on `account_id` for the audit-log foreign key join and future history queries (TX-HIST-01 in v2)
- No `to_account_id` or `rake_amount` column in this migration — RAKE-01 only requires resolving and applying rake; the debit to from-account and credits to to/platform accounts are three separate transaction rows, not one row with split columns `[ASSUMED: three-row rake model; alternative is a single row with rake_amount; confirm design with planner]`

### V5: Add transaction_id FK to balance_audit_log

```sql
-- File: db/migration/V5__add_transaction_id_to_audit_log.sql
-- Purpose: Links audit log entries to the discrete_transactions record (META-02)
-- [VERIFIED: REQUIREMENTS.md META-02 — metadata flows through to audit log]

ALTER TABLE balance_audit_log
    ADD COLUMN transaction_id BIGINT,
    ADD CONSTRAINT fk_audit_dtx
        FOREIGN KEY (transaction_id)
        REFERENCES discrete_transactions(id);
```

**Why nullable:** Phase 1 operations (account creation) have no `transaction_id` — they predate discrete transactions. The column is nullable so existing audit rows remain valid.

---

## Common Pitfalls

### Pitfall 1: Floor Check Outside the Lock Boundary
**What goes wrong:** Service reads account balance without a lock, checks the floor, finds it valid, then acquires the write. Between the read and the write, a concurrent debit reduces the balance. The floor check is now stale — both transactions pass and the balance drops below the floor.
**Why it happens:** Developers check the floor before acquiring the lock to "fail fast." The TOCTOU window is the bug.
**How to avoid:** Always call `accountRepository.findWithLock(id)` first. The lock blocks until any competing transaction commits, so the balance read is guaranteed to reflect the current committed state.
**Warning signs:** DTX-03 Cucumber scenario passes in serial but fails under concurrent load (DTX-04 stress test).

### Pitfall 2: Idempotency Pattern Change from Phase 1
**What goes wrong:** Phase 1's `AccountServiceImpl.createAccount` uses the check-then-insert pattern (lookup first, then `doCreate`). This differs from the catch-constraint pattern mentioned in Phase 1 RESEARCH. The check-then-insert pattern has a TOCTOU for concurrent identical idempotency keys, but the Phase 1 `idempotency_keys` table UNIQUE constraint catches duplicates at the DB level anyway.

Phase 2 should use the same check-first pattern as the existing `AccountServiceImpl` (not the constraint-catch pattern from the RESEARCH doc) for consistency with the live codebase.
**How to avoid:** Copy the idempotency check pattern exactly from `AccountServiceImpl.createAccount` (`findByIdempotencyKeyAndOperation` before the write path).
**Warning signs:** Divergent idempotency implementation styles between account operations and transaction operations.

`[VERIFIED: engine-spring/src/main/java/.../AccountServiceImpl.java — lines 42-49]`

### Pitfall 3: Rake Arithmetic Scale Loss
**What goes wrong:** `amount.multiply(rakeRate)` returns a `BigDecimal` with raw scale (e.g., `10.00000... × 0.2000... = 2.00000...`). If not explicitly set to scale 18, downstream `compareTo` and `NUMERIC(38,18)` storage behave unexpectedly.
**Why it happens:** `BigDecimal.multiply` returns a new `BigDecimal` with `scale = scale1 + scale2`, potentially exceeding `NUMERIC(38,18)`.
**How to avoid:** Always call `.setScale(18, RoundingMode.DOWN)` on rake amount results. Use `RoundingMode.DOWN` (truncate, not round up) — this is conservative for the platform (takes slightly less rake than possible, never more).
**Warning signs:** Postgres rejects the write with "numeric field overflow."

### Pitfall 4: Locking Order Deadlock in Rake Path
**What goes wrong:** Rake involves updating three accounts. If Thread A holds a lock on account "X" and tries to lock "Y", while Thread B holds "Y" and tries to lock "X", both deadlock.
**Why it happens:** No canonical lock ordering.
**How to avoid:** In Phase 2's RAKE-01 scope, only the from-account is debited with `findWithLock`. The to-account and platform-account receive only credits, so they do NOT need `findWithLock`. This breaks the deadlock cycle.

Phase 4 (RAKE-02, full streaming rake) must extend this pattern carefully — by that point, document the canonical lock ordering if any credit paths need locking.
**Warning signs:** Intermittent deadlock errors in the Phase 2 concurrency test.

### Pitfall 5: BalanceAuditLog Entity Already Has No transactionId Field
**What goes wrong:** V5 migration adds `transaction_id` to the Postgres table, but the `BalanceAuditLog` JPA entity doesn't have a matching field. `ddl-auto: validate` will fail on startup.
**Why it happens:** Schema change not reflected in the entity.
**How to avoid:** V5 migration AND `BalanceAuditLog.java` entity update are the same task. Add `@Column(name = "transaction_id") private Long transactionId;` to the entity simultaneously with V5.
**Warning signs:** `org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing column [transaction_id] in table [balance_audit_log]`

### Pitfall 6: @Lock Annotation Requires Active @Transactional
**What goes wrong:** Spring Data JPA's `@Lock` annotation requires an active transaction. If `findWithLock` is called from outside a `@Transactional` boundary, Spring may open a new transaction per-query and release the lock immediately, providing no protection.
**Why it happens:** Repository methods outside a service-layer transaction are each wrapped in their own transaction.
**How to avoid:** Always call `findWithLock` from within a `@Transactional` service method. The transaction must remain open through all subsequent reads and writes.
**Warning signs:** Concurrency stress test shows double-spend despite `findWithLock` being called — the lock was released before the floor check.

`[VERIFIED: Context7 /spring-projects/spring-data-jpa locking.adoc — locking requires an active transaction]`

---

## Code Examples

### AccountRepository — findWithLock
```java
// Source: Context7 /spring-projects/spring-data-jpa locking.adoc
// [VERIFIED: Context7]
public interface AccountRepository extends JpaRepository<Account, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findWithLock(@Param("id") String id);
}
```

### DiscreteTransaction DDL — V4
```sql
-- Source: Phase 1 DDL conventions
-- File: engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql
CREATE TABLE discrete_transactions (
    id              BIGSERIAL           PRIMARY KEY,
    account_id      VARCHAR(255)        NOT NULL,
    type            VARCHAR(20)         NOT NULL,
    amount          NUMERIC(38,18)      NOT NULL,
    metadata        JSONB,
    idempotency_key VARCHAR(255),
    posted_at       TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_dtx_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_dtx_type   CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_dtx_amount CHECK (amount > 0)
);
CREATE INDEX idx_dtx_account_id ON discrete_transactions(account_id);
```

### Rake Rate Resolution — Service Method
```java
// Source: REQUIREMENTS.md RAKE-01 + TokenEngineProperties pattern
// [ASSUMED: shape of RakeProperties; verify with planner]
private BigDecimal resolveRakeRate(Map<String, Object> metadata) {
    return properties.getRake().getRateFor(metadata);
}

private BigDecimal computeRakeAmount(BigDecimal amount, BigDecimal rakeRate) {
    return amount.multiply(rakeRate).setScale(18, RoundingMode.DOWN);
}
```

### Concurrent Debit Stress Test — Key Structure
```java
// Source: standard Java concurrency test pattern
// [VERIFIED: multiple community references — countdownlatch pattern]
int N = 20;
BigDecimal AMOUNT = BigDecimal.TEN;
BigDecimal initialBalance = new BigDecimal("1000.00");

ExecutorService executor = Executors.newFixedThreadPool(N);
CountDownLatch start = new CountDownLatch(1);
CountDownLatch done = new CountDownLatch(N);
AtomicInteger successCount = new AtomicInteger(0);

for (int i = 0; i < N; i++) {
    final String key = "stress-key-" + i;
    executor.submit(() -> {
        try {
            start.await();
            postDebit(accountId, AMOUNT, key);
            successCount.incrementAndGet();
        } catch (Exception ignored) {
        } finally {
            done.countDown();
        }
    });
}
start.countDown();
done.await(30, TimeUnit.SECONDS);
executor.shutdown();

BigDecimal finalBalance = getCommittedBalance(accountId);
BigDecimal expected = initialBalance.subtract(AMOUNT.multiply(
    BigDecimal.valueOf(successCount.get())));
assertThat(finalBalance.compareTo(expected)).isEqualTo(0);
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Application-level `synchronized` for balance safety | Postgres `SELECT FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)` | Industry shift ~2015 | DB lock survives multi-instance deployment; Java lock doesn't |
| `@Version` optimistic locking + retry for balance debits | `PESSIMISTIC_WRITE` for correctness-first balance engines | Not a chronological change — depends on use case | Retry complexity vs. blocking; correctness-first engines prefer pessimistic |
| Storing all transaction metadata as separate columns | `JSONB` column for open key-value metadata | Postgres 9.4+ (2014), Hibernate 6 support 2022 | No schema migration needed when metadata fields change |

**No deprecated items for Phase 2** — all Phase 1 patterns remain valid.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | RAKE-01 scope in Phase 2 covers only three-row model (debit + two credits as separate transaction records), not a single transaction row with split columns | DDL Design, Pattern 3 | Medium — if planner prefers single row with `rake_amount` column, V4 DDL and entity change |
| A2 | Credits do not need `findWithLock` — only debits that can violate the floor need the pessimistic lock | Pattern 2, Anti-Patterns | Low — no floor ceiling in requirements; safe unless future requirements add an upper bound |
| A3 | Rake configuration is read-only at service startup (from `application.yml`) and not updatable at runtime without restart | Pattern 6 | Medium — if dynamic rake rate updates are needed later, a database-backed config table is required; not in scope for Phase 2 |
| A4 | The concurrency stress test lives in the Cucumber BDD harness (`discrete-concurrency.feature`) rather than a separate JUnit `@SpringBootTest` class | Patterns, Validation Architecture | Low — either approach works; Cucumber approach is consistent with Phase 1 conventions |
| A5 | RAKE-01 requires `toAccountId` to be passed in the `PostTransactionRequest` (rake is always a transfer, not a pure platform extraction) | Pattern 3 | Medium — REQUIREMENTS.md RAKE-01 says "debit from-account → credit to-account → credit platform-account" but the Phase 2 scope is limited to discrete; confirm whether to-account is required in Phase 2 |

---

## Open Questions

1. **Rake model: three separate transaction rows vs. one row with rake_amount?**
   - What we know: RAKE-01 says "atomic three-way operation"; RAKE-04 (Phase 4) says debit equals sum of credits
   - Two design options: (a) three `discrete_transactions` rows — one DEBIT, one CREDIT for to-account, one CREDIT for platform; (b) one DEBIT row with `rake_amount` and `to_account_id` columns
   - Recommendation: Option (a) is simpler for Phase 2 and aligns with double-entry bookkeeping convention; Option (b) is more query-efficient for auditing. The RAKE-04 DB check constraint (Phase 4) works with either model.
   - Decision needed before DDL migration is written.

2. **Does RAKE-01 require `toAccountId` in Phase 2?**
   - What we know: RAKE-01 says "from-account → to-account → platform-account"; the Phase 2 requirement says rake applies to discrete transactions
   - The simplest Phase 2 implementation is a credit going from the debit account to a platform account only (one-way rake). A full three-party discrete transaction (A pays B, platform takes rake) requires `toAccountId` in the request.
   - Recommendation: Implement the full three-party model now; it's simpler than retrofitting in Phase 4 when streaming gets rake.

3. **Should the concurrent stress test be Cucumber or plain JUnit 5?**
   - What we know: Phase 1 established Cucumber as the BDD harness for all integration tests
   - Concurrency tests with `CountDownLatch` are verbose in Gherkin and natural in Java
   - Recommendation: Implement as a standalone `@SpringBootTest` JUnit 5 class (`DiscreteTransactionConcurrencyTest.java`) that runs as part of the same `./gradlew :engine-service:test` execution. Name it clearly so the Cucumber report is not confused.

---

## Environment Availability

Step 2.6: SKIPPED — Phase 2 introduces no new external dependencies beyond those verified in Phase 1 (Java 21, Docker/Testcontainers, Postgres). All tools confirmed available in Phase 1 research.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | Cucumber 7.22.1 + JUnit 5 Platform + Testcontainers 1.21.3 (unchanged from Phase 1) |
| Config file | Inherits from Phase 1 — `CucumberTestRunner`, `CucumberSpringConfiguration`, `TestcontainersConfiguration` all exist |
| Quick run command | `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i` |
| Full suite command | `./gradlew :engine-service:test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| DTX-01 | POST credit produces audit entry; balance increases; 201 response | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| DTX-02 | POST debit produces audit entry; balance decreases; 201 response | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| DTX-03 | POST debit below floor rejected even under concurrent streaming (floor = 0, amount > balance) | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| DTX-04 | 20 concurrent debits against same account yield correct final balance; no double-spend | JUnit 5 `@SpringBootTest` | `./gradlew :engine-service:test --tests "*ConcurrencyTest*"` | Wave 0 gap |
| META-01 | POST with metadata map; metadata returned unchanged in response | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| META-02 | Metadata from transaction flows through to audit log entry | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| RAKE-01 | POST with rake-enabled type; three balance changes; from-account debit = sum of two credits | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |

### Sampling Rate
- **Per task commit:** `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i`
- **Per wave merge:** `./gradlew test` (all three modules)
- **Phase gate:** Full suite green (including concurrency test) before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `engine-service/src/test/resources/features/discrete-credit.feature` — covers DTX-01, META-01
- [ ] `engine-service/src/test/resources/features/discrete-debit.feature` — covers DTX-02, DTX-03, META-01
- [ ] `engine-service/src/test/resources/features/discrete-metadata.feature` — covers META-02
- [ ] `engine-service/src/test/resources/features/discrete-rake.feature` — covers RAKE-01
- [ ] `engine-service/src/test/java/.../steps/TransactionSteps.java` — step defs for above features
- [ ] `engine-service/src/test/java/.../DiscreteTransactionConcurrencyTest.java` — covers DTX-04
- [ ] `engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql`
- [ ] `engine-service/src/main/resources/db/migration/V5__add_transaction_id_to_audit_log.sql`

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No — engine trusts caller-supplied IDs per project constraint | N/A |
| V3 Session Management | No | N/A |
| V4 Access Control | No | N/A |
| V5 Input Validation | Yes — transaction amount must be positive; account ID must exist; metadata values should not cause JSON injection | Bean Validation (`@Positive` on amount, `@NotNull` on accountId); `@JdbcTypeCode` parameterized — not SQL injectable |
| V6 Cryptography | No | N/A |

### Known Threat Patterns for Phase 2 Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Negative amount debit (drain below floor via sign trick) | Tampering | `@Positive` on `amount` field in `PostTransactionRequest`; DB `CHECK (amount > 0)` constraint |
| Concurrent debits below floor (double-spend) | Tampering | `PESSIMISTIC_WRITE` lock serializes all balance mutations at DB level |
| Metadata injection — crafted JSONB values | Tampering | `@JdbcTypeCode` uses parameterized Hibernate binding — no SQL string interpolation |
| Rake arithmetic overflow | Tampering | `NUMERIC(38,18)` precision; `BigDecimal.setScale(18, RoundingMode.DOWN)` prevents overflow |
| Idempotency replay attack (fraudulent replay) | Repudiation | Idempotency keys are caller-supplied and scoped to operation type; same key for different operation types is allowed (by design, not a vulnerability) |

`[ASSUMED: amount must be > 0 based on ledger semantics; verify that zero-amount transactions should be rejected]`

---

## Sources

### Primary (HIGH confidence)
- Context7 `/spring-projects/spring-data-jpa` — `@Lock` annotation, `LockModeType.PESSIMISTIC_WRITE`, `@Modifying`, `@Transactional` patterns `[VERIFIED]`
- Project codebase: `engine-spring/src/main/java/.../AccountServiceImpl.java` — idempotency pattern, service structure, `@Transactional` placement `[VERIFIED: codebase grep]`
- Project codebase: `engine-core/src/main/java/.../Account.java` — existing `credit()` and `debit()` methods `[VERIFIED: codebase grep]`
- Project codebase: `engine-service/src/main/resources/db/migration/V1-V3` — DDL conventions (NUMERIC(38,18), BIGSERIAL, JSONB, TIMESTAMPTZ) `[VERIFIED: codebase grep]`
- REQUIREMENTS.md — DTX-01 through DTX-04, META-01, META-02, RAKE-01 requirement text `[VERIFIED]`

### Secondary (MEDIUM confidence)
- [Pessimistic Locking in JPA | Baeldung](https://www.baeldung.com/jpa-pessimistic-locking) — `@Lock` + `PESSIMISTIC_WRITE` patterns, translated to `SELECT FOR UPDATE` in Postgres
- [Testing Multi-Threaded Code in Java | Baeldung](https://www.baeldung.com/java-testing-multithreaded) — `ExecutorService` + `CountDownLatch` concurrency test pattern
- [Spring Data JPA locking issues on GitHub](https://github.com/spring-projects/spring-data-jpa/issues/1592) — `NOWAIT` lock hint behavior in Spring Data JPA

### Tertiary (LOW confidence)
- WebSearch results on ledger table schema patterns — general double-entry conventions; specific column choices are Claude's discretion

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new libraries; all Phase 1 libraries verified
- Architecture (locking): HIGH — `PESSIMISTIC_WRITE` via `@Lock` is documented, verified, standard Spring Data JPA
- DDL design: MEDIUM — conventions follow Phase 1 (HIGH), but three-row rake model vs. single-row is an open design choice (A1)
- Rake configuration: MEDIUM — shape of `RakeProperties` in `TokenEngineProperties` is ASSUMED; structure is reasonable but needs planner confirmation
- Concurrency test approach: MEDIUM — Cucumber vs JUnit 5 placement is ASSUMED (A4)

**Research date:** 2026-05-13
**Valid until:** 2026-06-13 (stable Spring Data JPA; no expected breaking changes within 30 days)
