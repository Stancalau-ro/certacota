---
phase: 02-discrete-transactions
fixed_at: 2026-05-13T23:01:30Z
review_path: .planning/phases/02-discrete-transactions/02-REVIEW.md
iteration: 1
findings_in_scope: 10
fixed: 10
skipped: 0
status: all_fixed
---

# Phase 02: Code Review Fix Report

**Fixed at:** 2026-05-13T23:01:30Z
**Source review:** .planning/phases/02-discrete-transactions/02-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 10
- Fixed: 10
- Skipped: 0

## Fixed Issues

### CR-01: `toAccount` and `platformAccount` acquired without pessimistic lock in rake path

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java`
**Commit:** a45fb63
**Applied fix:** Replaced `accountRepository.findById(...)` with `accountRepository.findWithLock(...)` for both `toAccount` and `platformAccount` in `doDebitWithRake`. Restructured the method to acquire all locks first (from→to→platform consistent ordering) before any mutations, preventing lost-update races on credit recipients.

---

### CR-02: `new BigDecimal(rateStr)` throws uncaught `NumberFormatException`

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java`
**Commit:** 4865b55
**Applied fix:** Wrapped `new BigDecimal(rateStr)` in a try-catch block in `getRateFor`. On `NumberFormatException`, logs a warning via `@Slf4j` and returns `BigDecimal.ZERO` instead of propagating as a 500 error. Added `@Slf4j` to the `RakeProperties` static nested class.

---

### CR-03: Idempotency check before `findWithLock` — race window

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java`
**Commit:** 9ac6261
**Applied fix:** Removed the idempotency lookup from the public `credit()` and `debit()` methods. Moved it inside `doCredit()` and `doDebit()` respectively, immediately after `accountRepository.findWithLock(...)` is called. The idempotency check now runs while holding the pessimistic lock, eliminating the race window where two concurrent requests with the same key could both proceed to execute.

---

### CR-04: `TokenEngineProperties` bean mutated in Cucumber step, no reset between scenarios

**Files modified:** `engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java`
**Commit:** 96e277f
**Applied fix:** Added a `@Before` (Cucumber `io.cucumber.java.Before`) hook method `resetRakeProperties()` that runs before each scenario. It resets rake to `enabled=false`, `metadataKey="transaction_type"`, clears the rates map, and sets `platformAccountId=null`, preventing state pollution across scenarios.

---

### WR-01: `doDebitWithRake` has no floor check

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java`
**Commit:** 88cc481
**Applied fix:** Added an explicit floor guard at the top of `doDebitWithRake` that computes `effectiveFloor` and `resultingBalance` and throws `BalanceFloorViolationException` if the balance would go below the floor. This makes `doDebitWithRake` safe to call independently without relying on a prior check in the caller.

---

### WR-02: No unique constraint on `discrete_transactions.idempotency_key`

**Files modified:** `engine-service/src/main/resources/db/migration/V6__add_unique_idempotency_key_to_discrete_transactions.sql` (new file)
**Commit:** 6818ae6
**Applied fix:** Created Flyway migration V6 that adds `UNIQUE (idempotency_key)` constraint to `discrete_transactions` via `ALTER TABLE ... ADD CONSTRAINT uq_dtx_idempotency_key UNIQUE (idempotency_key)`. This prevents duplicate idempotency keys from being inserted if the application-level check is bypassed under race conditions.

---

### WR-03: Concurrency test `failCount >= 0` assertion vacuously true

**Files modified:** `engine-service/src/test/java/com/certacota/engine/service/DiscreteTransactionConcurrencyTest.java`
**Commit:** ed60597
**Applied fix:** Reduced the initial account balance from `200.00` to `100.00` (while keeping 20 threads each debiting `10`), so that at most 10 can succeed and at least 10 must fail. Replaced the vacuous `assertThat(failCount.get()).isGreaterThanOrEqualTo(0)` with three meaningful assertions: `successCount <= 10` (cannot exceed balance/amount), `failCount > 0` (some must be rejected), and `finalBalance >= 0` (balance never goes negative).

---

### WR-04: `TransactionController` dispatches on `TransactionType` with no handling for future enum values

**Files modified:** `engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java`
**Commit:** c92f8d0
**Applied fix:** Replaced the `if/else` dispatch with an exhaustive `switch` expression (`return switch (request.type()) { case CREDIT -> ...; case DEBIT -> ...; }`). Adding a new `TransactionType` enum value without updating the switch causes a compile error.

---

### IN-01: `toAccountId` not validated as non-blank when present

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java`
**Commit:** d2b5ed0
**Applied fix:** Added a service-level guard in `doDebit()` that checks `request.toAccountId().isBlank()` when `toAccountId` is non-null (before routing to `doDebitWithRake`), throwing `IllegalArgumentException` for blank strings. Note: `@NotBlank` was not used on the record field because Jakarta Validation's `@NotBlank` rejects null values, which would break all transactions that do not include a `toAccountId`. The service-layer guard correctly allows null (optional field) while rejecting empty strings.

---

### IN-02: `BalanceAuditLog.recordedAt` missing `nullable = false`

**Files modified:** `engine-core/src/main/java/com/certacota/engine/core/domain/BalanceAuditLog.java`
**Commit:** 746f29d
**Applied fix:** Added `nullable = false` to the `@Column` annotation on `recordedAt`, making the entity definition explicit that this field is required. The column annotation now reads `@Column(name = "recorded_at", nullable = false, updatable = false)`.

---

## Test Results

All 10 findings were fixed. Full test suite (`./gradlew :engine-service:test --rerun-tasks`) passed after all fixes were applied. Build output: `BUILD SUCCESSFUL`.

---

_Fixed: 2026-05-13T23:01:30Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
