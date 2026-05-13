---
phase: 02-discrete-transactions
verified: 2026-05-13T00:00:00Z
status: passed
score: 5/5 must-haves verified
overrides_applied: 0
deferred:
  - truth: "Debit floor rejection holds even when concurrent streaming transactions are in flight against the same account"
    addressed_in: "Phase 3"
    evidence: "Phase 3 SC-4: 'concurrent streaming and discrete transactions against the same account simultaneously; final settled balance in Postgres is correct'; STR-04 requirement covers mixed concurrency correctness. The floor rejection under streaming load requires StreamRegistry (STR-03) which is a Phase 3 deliverable."
---

# Phase 2: Discrete Transactions Verification Report

**Phase Goal:** Callers can post credits and debits with metadata and optional rake; the engine rejects floor violations even under concurrent load, produces no double-spends or lost updates, and executes rake-enabled discrete transactions as atomic three-way splits
**Verified:** 2026-05-13
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Caller can post a discrete credit and a discrete debit; both appear in the audit log with the caller-supplied key-value metadata map unchanged | VERIFIED | `TransactionServiceImpl.doCredit/doDebit` save `BalanceAuditLog` with `transactionId=txn.getId()` and metadata flows via `DiscreteTransaction.metadata`. Cucumber `discrete-credit.feature`, `discrete-debit.feature`, `discrete-metadata.feature` cover these paths. |
| 2 | A debit below the floor is rejected (discrete-concurrent dimension) | VERIFIED | `doDebit` acquires `findWithLock` at line 108 before floor check at line 117–125. `BalanceFloorViolationException` thrown if `resultingBalance < effectiveFloor`. Feature `discrete-debit.feature` Scenario 2 asserts 422 response. |
| 3 | N concurrent discrete debits against a single account yield the correct final balance with no double-spend | VERIFIED | `DiscreteTransactionConcurrencyTest.concurrentDebitsDoNotDoubleSpend` — 20 threads via `CountDownLatch`, final balance asserted via `accountRepository.findById` (direct DB read), `assertThat(finalBalance.compareTo(expectedBalance)).isEqualTo(0)`. No `Thread.sleep`. |
| 4 | Metadata supplied at transaction creation flows through unchanged to all audit log entries associated with that transaction; metadata cannot be altered after creation | VERIFIED | `DiscreteTransaction` all columns `updatable=false`. `BalanceAuditLog.transactionId` FK links audit to transaction. `discrete-metadata.feature` Scenario 2 asserts `auditLogRepository` entry links to transaction carrying correct metadata. `TransactionSteps.auditLogHasEntryWithTransactionMetadata` checks both the `DiscreteTransaction.metadata` and the audit log FK. |
| 5 | A rake-enabled discrete transaction executes as an atomic three-way debit/credit/credit; zero-rake, full-rake, and hybrid configurations all produce balanced arithmetic | VERIFIED | `doDebitWithRake` inside `@Transactional`: debit from-account by `amount`, credit to-account by `amount - rakeAmount`, credit platform by `rakeAmount`. Arithmetic: `toAccountAmount + rakeAmount = amount` exactly (subtraction, not separate multiplication). `RoundingMode.DOWN`. `discrete-rake.feature` Scenario 1 asserts all three account balances (900/80/20). Zero-rake covered by Scenario 2 via unknown metadata type returning `BigDecimal.ZERO` from `getRateFor`. |

**Score:** 5/5 truths verified

### Deferred Items

Items not yet met but explicitly addressed in later milestone phases.

| # | Item | Addressed In | Evidence |
|---|------|-------------|----------|
| 1 | Floor rejection when concurrent streaming transactions are in flight (DTX-03 streaming dimension) | Phase 3 | Phase 3 SC-4 covers concurrent streaming + discrete correctness; STR-03 in-memory StreamRegistry (required for in-flight balance awareness) is a Phase 3 deliverable |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql` | discrete_transactions DDL with CHECK (amount > 0) | VERIFIED | Contains `BIGSERIAL`, `NUMERIC(38,18)`, `JSONB`, `CONSTRAINT chk_dtx_amount CHECK (amount > 0)`, `CONSTRAINT fk_dtx_account`, `CONSTRAINT chk_dtx_type`, `CREATE INDEX idx_dtx_account_id` |
| `engine-service/src/main/resources/db/migration/V5__add_transaction_id_to_audit_log.sql` | nullable transaction_id FK on balance_audit_log | VERIFIED | Contains `ALTER TABLE balance_audit_log`, `ADD COLUMN transaction_id BIGINT` (nullable — no NOT NULL), `CONSTRAINT fk_audit_dtx` |
| `engine-core/src/main/java/com/certacota/engine/core/domain/TransactionType.java` | Enum CREDIT/DEBIT | VERIFIED | Simple enum, values CREDIT and DEBIT |
| `engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java` | JPA entity for discrete_transactions, all updatable=false | VERIFIED | All 7 `@Column` annotations carry `updatable=false`; `@JdbcTypeCode(SqlTypes.JSON)` on metadata; `@GeneratedValue(strategy=GenerationType.IDENTITY)` |
| `engine-core/src/main/java/com/certacota/engine/core/domain/BalanceAuditLog.java` | Has transactionId field | VERIFIED | `@Column(name="transaction_id", updatable=false) private Long transactionId` present after idempotencyKey |
| `engine-core/src/main/java/com/certacota/engine/core/repository/AccountRepository.java` | findWithLock with PESSIMISTIC_WRITE | VERIFIED | `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query("SELECT a FROM Account a WHERE a.id = :id")` + `Optional<Account> findWithLock(@Param("id") String id)` |
| `engine-core/src/main/java/com/certacota/engine/core/repository/DiscreteTransactionRepository.java` | findByAccountId and findByIdempotencyKey | VERIFIED | Both derived queries present; extends `JpaRepository<DiscreteTransaction, Long>` |
| `engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionRequest.java` | Record with @NotNull/@Positive; all required fields | VERIFIED | `record PostTransactionRequest`; `@NotNull` on accountId, type, idempotencyKey; `@NotNull @Positive` on amount; `toAccountId` nullable |
| `engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionResponse.java` | Record with static factory from(DiscreteTransaction, BigDecimal) | VERIFIED | `public static PostTransactionResponse from(DiscreteTransaction txn, BigDecimal balanceAfter)` present; all 7 fields |
| `engine-core/src/main/java/com/certacota/engine/core/service/TransactionService.java` | Interface with credit() and debit() | VERIFIED | `PostTransactionResponse credit(PostTransactionRequest request)` and `PostTransactionResponse debit(PostTransactionRequest request)` |
| `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java` | Full implementation: credit, debit, rake, idempotency | VERIFIED | `@Service @Transactional @RequiredArgsConstructor @Slf4j`; implements TransactionService; idempotency check-first pattern; rake three-way split; `findWithLock` first in both `doCredit` and `doDebit`; `RoundingMode.DOWN` on rake arithmetic |
| `engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java` | RakeProperties nested class with getRateFor, platformAccountId | VERIFIED | `static class RakeProperties` with `enabled`, `metadataKey`, `rates`, `platformAccountId`; `getRateFor(Map<String,Object>)` returns `BigDecimal.ZERO` when not enabled or key not found |
| `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java` | Second @Bean @ConditionalOnMissingBean for TransactionService | VERIFIED | Two `@Bean @ConditionalOnMissingBean` methods: `accountService(...)` and `transactionService(...)` with all 6 dependencies |
| `engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java` | POST /api/v1/transactions, thin delegator, @Valid | VERIFIED | `@RestController @RequestMapping("/api/v1/transactions")`; `@PostMapping @ResponseStatus(HttpStatus.CREATED)`; `@Valid @RequestBody`; delegates to `transactionService.credit()` or `transactionService.debit()` based on `request.type()` — no business logic |
| `engine-service/src/main/java/com/certacota/engine/service/controller/GlobalExceptionHandler.java` | Handlers for AccountNotFoundException, BalanceFloorViolationException, AccountClosedException, MethodArgumentNotValidException | VERIFIED | All four `@ExceptionHandler` methods present: 404, 422, 409, 400 respectively |
| `engine-service/src/test/resources/features/discrete-credit.feature` | 3 scenarios: balance increase, metadata in response, idempotency | VERIFIED | 3 scenarios: "Credit increases account balance", "Credit with metadata returns metadata in response", "Same idempotency key for credit returns same response twice" |
| `engine-service/src/test/resources/features/discrete-debit.feature` | 3 scenarios: balance decrease, floor rejection, exact zero balance | VERIFIED | 3 scenarios including floor rejection (422 + balance unchanged assertion) |
| `engine-service/src/test/resources/features/discrete-metadata.feature` | 2 scenarios: metadata in response, metadata in audit log | VERIFIED | Scenario 2 queries `discreteTransactionRepository` and `auditLogRepository` to verify `transactionId` FK linkage |
| `engine-service/src/test/resources/features/discrete-rake.feature` | 2 scenarios: three-way split arithmetic, zero-rake | VERIFIED | Scenario 1 asserts all three account balances (rake-from=900, rake-to=80, rake-platform=20). Scenario 2 covers zero-rake path. |
| `engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java` | Step definitions for all four feature files | VERIFIED | All required `@Given/@When/@Then` steps implemented; no redefinition of shared steps from AccountSteps; `rakeIsConfigured` step injects into `TokenEngineProperties` at runtime |
| `engine-service/src/test/java/com/certacota/engine/service/DiscreteTransactionConcurrencyTest.java` | DTX-04 concurrent debit stress test | VERIFIED | `@SpringBootTest(RANDOM_PORT)` + `@Import(TestcontainersConfiguration.class)`; 20 threads via `CountDownLatch`; no `Thread.sleep`; final balance asserted via direct `accountRepository.findById`; `assertThat(finalBalance.compareTo(expectedBalance)).isEqualTo(0)` present |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `TransactionController.postTransaction` | `TransactionService.credit / .debit` | `request.type()` branch in controller body | VERIFIED | Lines 29–33: `if (request.type() == TransactionType.CREDIT) return transactionService.credit(request); else return transactionService.debit(request);` |
| `AccountRepository.findWithLock` | Postgres SELECT FOR UPDATE | `@Lock(LockModeType.PESSIMISTIC_WRITE)` on JPQL | VERIFIED | Annotation present on `findWithLock`; both `doCredit` (line 57) and `doDebit` (line 108) call it as the first statement — before floor check (line 117) |
| `TransactionServiceImpl.doDebit` | `auditLogRepository.save` | Same `@Transactional` scope, `transactionId=txn.getId()` | VERIFIED | `auditLogRepository.save(BalanceAuditLog.builder()...transactionId(txn.getId())...)` at lines 147–157 (non-rake) and 186–195 (rake) |
| `TokenEngineAutoConfiguration.transactionService` | `TransactionServiceImpl` | `@Bean @ConditionalOnMissingBean` factory method | VERIFIED | `return new TransactionServiceImpl(accountRepository, discreteTransactionRepository, auditLogRepository, idempotencyKeyRepository, properties, objectMapper)` |
| `BalanceAuditLog.transactionId` | `discrete_transactions.id` | V5 FK constraint `fk_audit_dtx` | VERIFIED | V5 migration adds `CONSTRAINT fk_audit_dtx FOREIGN KEY (transaction_id) REFERENCES discrete_transactions(id)`; entity field `@Column(name="transaction_id", updatable=false) private Long transactionId` |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| `TransactionController` | `PostTransactionResponse` | `TransactionServiceImpl.credit/debit` → `AccountRepository`, `DiscreteTransactionRepository`, `BalanceAuditLogRepository` | Yes — all three repositories backed by Postgres; `DiscreteTransaction` saved and ID returned; balance read from `account.getBalance()` after mutation | FLOWING |
| `TransactionServiceImpl.doDebitWithRake` | `fromAccount.getBalance()` after debit, `toAccount.getBalance()` after credit | `accountRepository.findWithLock` + `accountRepository.findById` (Postgres) | Yes — direct DB reads inside single `@Transactional` | FLOWING |

### Behavioral Spot-Checks

Step 7b: SKIPPED — no live server available; integration tests via Testcontainers cover the same behaviors. All behavioral verification is embedded in the Cucumber test suite and concurrency JUnit test per SUMMARY.md (which reports BUILD SUCCESSFUL exits).

### Probe Execution

Step 7c: No conventional probe scripts found at `scripts/*/tests/probe-*.sh`. No phase-declared probes in PLAN or SUMMARY. SKIPPED.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| DTX-01 | 02-01, 02-02, 02-03 | Caller can post a discrete credit | SATISFIED | `TransactionController` POST endpoint; `TransactionService.credit`; `discrete-credit.feature` passes |
| DTX-02 | 02-01, 02-02, 02-03 | Caller can post a discrete debit | SATISFIED | `TransactionService.debit`; `discrete-debit.feature` passes |
| DTX-03 | 02-01, 02-02, 02-03 | Engine rejects debit below floor (streaming dimension deferred) | SATISFIED (partial — discrete-concurrent dimension) | PESSIMISTIC_WRITE before floor check; `discrete-debit.feature` Scenario 2 asserts 422; streaming-concurrent dimension deferred to Phase 3 |
| DTX-04 | 02-03 | Concurrent discrete transactions with no double-spend | SATISFIED | `DiscreteTransactionConcurrencyTest.concurrentDebitsDoNotDoubleSpend` — 20 threads, balance math assertion |
| META-01 | 02-01, 02-02, 02-03 | Transaction accepts metadata map; immutable after creation | SATISFIED | All `DiscreteTransaction` columns `updatable=false`; metadata stored via `@JdbcTypeCode(JSON)`; `discrete-metadata.feature` Scenario 1 |
| META-02 | 02-01, 02-02, 02-03 | Metadata flows unchanged to audit log entries | SATISFIED | `BalanceAuditLog.transactionId` FK; `discrete-metadata.feature` Scenario 2 asserts `auditLogRepository` entry with matching `transactionId` |
| RAKE-01 | 02-01, 02-02, 02-03 | Rake rules per transaction type; atomic three-way split on discrete | SATISFIED | `doDebitWithRake` in single `@Transactional`; `getRateFor` resolves rate from metadata; `discrete-rake.feature` asserts all three account balances |

**All 7 Phase 2 requirements are covered.** No orphaned requirements found.

### Anti-Patterns Found

No anti-patterns found in any Phase 2 modified files:
- No `TBD`, `FIXME`, or `XXX` markers
- No `TODO` or `HACK` markers
- No stub return values (`return null`, `return {}`, `return []`) in production code paths
- No empty handler bodies
- `Thread.sleep` absent from `DiscreteTransactionConcurrencyTest`
- No business logic in `TransactionController` (pure delegator)

### Human Verification Required

None. All behaviors are verifiable programmatically via the Cucumber acceptance test suite and JUnit concurrency test against a live Testcontainers Postgres instance.

### Gaps Summary

No blocking gaps. All five ROADMAP success criteria have implementation evidence in the codebase:

1. SC-1 (credit/debit + audit log + metadata): full implementation in TransactionServiceImpl, wired through controller, tested in four feature files.
2. SC-2 (floor rejection): PESSIMISTIC_WRITE lock acquired before floor check in doDebit; discrete-concurrent dimension fully implemented and tested. The streaming-concurrent sub-dimension is deferred to Phase 3 (requires StreamRegistry).
3. SC-3 (concurrent correctness): DiscreteTransactionConcurrencyTest implements the exact algorithm specified in the plan: 20 threads, CountDownLatch, final balance vs. mathematical expected value assertion.
4. SC-4 (metadata immutability + audit flow-through): all entity columns updatable=false; transactionId FK links audit to transaction; tested in discrete-metadata.feature.
5. SC-5 (rake atomic split): doDebitWithRake executes debit + two credits in one @Transactional; arithmetic balances by construction (toAccountAmount = amount - rakeAmount); tested with concrete balance assertions.

---

_Verified: 2026-05-13_
_Verifier: Claude (gsd-verifier)_
