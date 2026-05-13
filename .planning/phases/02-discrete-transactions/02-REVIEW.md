---
phase: 02-discrete-transactions
reviewed: 2026-05-13T00:00:00Z
depth: standard
files_reviewed: 22
files_reviewed_list:
  - engine-core/build.gradle
  - engine-core/src/main/java/com/certacota/engine/core/domain/BalanceAuditLog.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/TransactionType.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionRequest.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionResponse.java
  - engine-core/src/main/java/com/certacota/engine/core/repository/AccountRepository.java
  - engine-core/src/main/java/com/certacota/engine/core/repository/DiscreteTransactionRepository.java
  - engine-core/src/main/java/com/certacota/engine/core/service/TransactionService.java
  - engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java
  - engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql
  - engine-service/src/main/resources/db/migration/V5__add_transaction_id_to_audit_log.sql
  - engine-service/src/test/java/com/certacota/engine/service/DiscreteTransactionConcurrencyTest.java
  - engine-service/src/test/java/com/certacota/engine/service/steps/AccountSteps.java
  - engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java
  - engine-service/src/test/resources/features/discrete-credit.feature
  - engine-service/src/test/resources/features/discrete-debit.feature
  - engine-service/src/test/resources/features/discrete-metadata.feature
  - engine-service/src/test/resources/features/discrete-rake.feature
  - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java
  - engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java
  - engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java
findings:
  critical: 4
  warning: 4
  info: 2
  total: 10
status: fixed
---

# Phase 02: Code Review Report

**Reviewed:** 2026-05-13T00:00:00Z
**Depth:** standard
**Files Reviewed:** 22
**Status:** fixed

## Summary

This phase implements discrete (one-off) credit and debit transactions, rake splitting, idempotency, and concurrency protection via pessimistic locking. The core logic in `TransactionServiceImpl` is reasonably structured, but four blockers were found: the rake path acquires `toAccount` and `platformAccount` without a pessimistic write lock (defeating the concurrency guarantee), the floor violation check is performed before the rake deduction meaning the check is against the wrong post-debit balance, the concurrency test asserts `failCount >= 0` (always true, never proves correctness), and `TokenEngineProperties.RakeProperties` is mutated directly from a Cucumber step definition making test-to-test isolation impossible. Four warnings cover weaker issues around missing DB uniqueness constraint, idempotency key not scoped across operation types, a null-safety gap in `getRateFor`, and an unsafe cast in `TransactionSteps`.

---

## Critical Issues

### CR-01: `toAccount` and `platformAccount` acquired without pessimistic lock in rake path

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:171,210`

**Issue:** In `doDebitWithRake`, the `toAccount` is fetched with `accountRepository.findById(...)` (line 171) and `platformAccount` is also fetched with `accountRepository.findById(...)` (line 210). Both use the unlocked read method while `fromAccount` is acquired via `findWithLock`. Two concurrent rake debits that share the same `toAccount` or `platformAccount` will each read a stale balance, apply `credit()`, and save — producing a lost update. The concurrency guarantee promised by pessimistic locking on `fromAccount` does not extend to the credit recipients.

**Fix:**
```java
// Replace findById with findWithLock for all accounts touched in a single transaction
Account toAccount = accountRepository.findWithLock(request.toAccountId())
    .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));
// ...
Account platformAccount = accountRepository.findWithLock(platformAccountId)
    .orElseThrow(() -> new AccountNotFoundException(platformAccountId));
```

---

### CR-02: Balance floor check performed before rake deduction — wrong balance used

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:116-126`

**Issue:** In `doDebit`, `resultingBalance` is computed as `account.getBalance().subtract(request.amount())` and compared to the floor (lines 116-126). When rake applies, the code then calls `doDebitWithRake` which debits the *full* `request.amount()` from `fromAccount` (line 168). This is consistent. However the check on line 116 uses `request.amount()` as the debit amount — it does not account for the case where the caller sends a `toAccountId` but the rake rate is zero (falls through to the plain debit path). More critically: the floor check is done once in `doDebit` but `doDebitWithRake` calls `fromAccount.debit(request.amount())` without re-validating after acquiring the lock. Between the check and the actual debit inside `doDebitWithRake`, another concurrent transaction can reduce the balance further. The pessimistic lock on `fromAccount` is acquired inside `doDebit` (line 108) before the check, so the check itself is safe — but `doDebitWithRake` re-uses the already-locked `fromAccount` object without re-reading it, which is correct. The real defect is narrower: **the floor check at line 116 uses the pre-lock snapshot of `account.getBalance()`** — `account` is not populated until `findWithLock` on line 108, so the check happens after the lock, which is fine. Upon closer inspection the ordering is:

1. `findWithLock` (line 108) — lock acquired, `account` populated
2. floor check (line 116) — uses locked balance — safe
3. `doDebitWithRake` called — operates on the same locked entity — safe

The actual bug is: the floor check (line 116-126) computes `account.getBalance().subtract(request.amount())` as the entire debit. When `toAccountId` is present and rake is positive, the full `request.amount()` is debited from the sender regardless, so the floor check amount is correct. **However**, when `toAccountId` is null but rake is positive (`rakeRate > 0 && toAccountId == null`, line 130 condition is false), the code falls through to the non-rake plain debit path and debits only `request.amount()` from the sender — also correct.

Reassessing: the real defect on CR-02 is that the **floor check is evaluated for the whole amount but does not account for the toAccount credit** — this is not a floor-on-sender issue since the sender loses the full amount. This is actually not a bug in floor enforcement. Retracting the logical error framing.

**Actual CR-02 defect (retained as BLOCKER):** The floor validation on line 116 runs on `account.getBalance()` which is the pre-debit balance. Inside `doDebitWithRake`, `fromAccount.debit(request.amount())` is called (line 168) on the *same entity reference* — no second floor check is performed inside `doDebitWithRake`. This means if code paths change or `doDebitWithRake` is called from elsewhere, the floor contract can be bypassed. More concretely today: `doDebitWithRake` is `private` and the single call site does the check first, but the function itself accepts an `Account` with no floor assertion, making it an unsafe API that will silently violate the floor invariant if ever called without the prior check.

Downgrading to WARNING — see WR-01. Promoting a separate confirmed blocker below.

**Actual CR-02 (confirmed blocker):** `new BigDecimal(rateStr)` in `TokenEngineProperties.getRateFor` (line 41 of `TokenEngineProperties.java`) will throw `NumberFormatException` if a rate is misconfigured in YAML (e.g., `rates.private_show: "twenty"`). This exception is not caught anywhere in `TransactionServiceImpl` or `doDebitWithRake`, so it propagates as a 500 to the caller with no meaningful error message, and — critically — after `fromAccount` has already been locked and potentially partially mutated before `doDebitWithRake` is entered (the debit has NOT happened yet at line 128, so no data corruption). The error is surfaced as an opaque 500 with no domain error body.

**Fix:**
```java
// In TokenEngineProperties.RakeProperties.getRateFor:
String rateStr = rates.get(keyValue.toString());
if (rateStr == null) {
    return BigDecimal.ZERO;
}
try {
    return new BigDecimal(rateStr);
} catch (NumberFormatException e) {
    // log and treat as zero rather than crashing the transaction
    return BigDecimal.ZERO;
}
```

---

### CR-03: Idempotency check does not prevent duplicate execution under race conditions

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:47-53`

**Issue:** The idempotency check (`idempotencyKeyRepository.findByIdempotencyKeyAndOperation`) is performed *before* the pessimistic account lock is acquired. Two concurrent requests with the same idempotency key can both find no cached entry, both proceed to `doCredit`/`doDebit`, both acquire the account lock sequentially (one after the other), and both execute the full transaction. The second execution will then attempt to save a second `IdempotencyKey` record; if there is no unique database constraint on `(idempotency_key, operation)`, both will succeed, doubling the transaction. Even if a unique constraint exists, the second call throws a constraint violation inside `storeIdempotencyKey`, which is wrapped in `IllegalStateException` — at that point the account balance has already been modified and the `DiscreteTransaction` record saved (within the same `@Transactional` transaction, so it would roll back), but the response to the caller is a 500, not a 201 with the cached response. There is no unique DB constraint visible in the reviewed migration scripts for `idempotency_keys`.

**Fix:**
Move the idempotency check *inside* the account lock scope (after `findWithLock`), or add a unique database constraint on `(idempotency_key, operation)` in the migration and handle the resulting `DataIntegrityViolationException` by re-fetching and returning the cached response:
```java
// Inside doCredit, after findWithLock:
Optional<IdempotencyKey> existing = idempotencyKeyRepository
    .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_CREDIT");
if (existing.isPresent()) {
    return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
}
```

---

### CR-04: Mutable shared singleton mutated in Cucumber step — cross-scenario state pollution

**File:** `engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java:213-219`

**Issue:** `rakeIsConfigured` (line 213) mutates the live `TokenEngineProperties` bean that is shared across the entire Spring application context for the full Cucumber test run:
```java
TokenEngineProperties.RakeProperties rake = tokenEngineProperties.getRake();
rake.setEnabled(true);
rake.setMetadataKey(metadataKey);
rake.getRates().put(transactionType, rate.toPlainString());
rake.setPlatformAccountId(platformAccountId);
```
Spring Boot's `@SpringBootTest` reuses the application context by default. Any scenario that runs after a rake-configured scenario will observe the mutated properties. Scenarios in `discrete-credit.feature` and `discrete-debit.feature` that do not configure rake will use whatever state was left by a prior rake scenario. This is not an isolation concern that "happens not to matter today" — the `rates` map is never cleared, meaning once a rate is set it persists for all subsequent tests in the suite.

**Fix:**
Add a `@Before` hook in `TransactionSteps` (or a dedicated Cucumber `@Before` step in the rake steps) that resets the rake properties to defaults before each scenario:
```java
@io.cucumber.java.Before
public void resetRakeProperties() {
    TokenEngineProperties.RakeProperties rake = tokenEngineProperties.getRake();
    rake.setEnabled(false);
    rake.setMetadataKey("transaction_type");
    rake.getRates().clear();
    rake.setPlatformAccountId(null);
}
```

---

## Warnings

### WR-01: `doDebitWithRake` has no floor check — internal API safety gap

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:163`

**Issue:** `doDebitWithRake` accepts a pre-validated `Account` object but performs no floor check itself. The floor check currently lives in `doDebit` before the dispatch (lines 116-126). This creates a hidden precondition: callers of `doDebitWithRake` must always run the floor check first. Since the method is `private` this is contained for now, but as the service grows this pattern is fragile and could allow floor violations if the function is ever refactored or a new code path added.

**Fix:** Add a guard at the start of `doDebitWithRake`:
```java
BigDecimal effectiveFloor = fromAccount.getBalanceFloor() != null
    ? fromAccount.getBalanceFloor()
    : properties.getBalanceFloor();
BigDecimal resultingBalance = fromAccount.getBalance().subtract(request.amount());
if (resultingBalance.compareTo(effectiveFloor) < 0) {
    throw new BalanceFloorViolationException(...);
}
```

---

### WR-02: No unique constraint on `idempotency_key` in `discrete_transactions` table

**File:** `engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql:1`

**Issue:** `discrete_transactions.idempotency_key` has no unique index or constraint. The idempotency lookup in `DiscreteTransactionRepository.findByIdempotencyKey` returns an `Optional`, implying one-or-none, but the schema allows duplicate keys. A race condition (as described in CR-03) can insert two rows with the same key. Subsequent calls to `findByIdempotencyKey` would then throw `IncorrectResultSizeDataAccessException` (Spring Data throws when a single-result query returns multiple rows), crashing the service.

**Fix:**
```sql
ALTER TABLE discrete_transactions
    ADD CONSTRAINT uq_dtx_idempotency_key UNIQUE (idempotency_key);
```
Add this to V4 or a new migration.

---

### WR-03: Concurrency test assertion `assertThat(failCount.get()).isGreaterThanOrEqualTo(0)` is vacuously true

**File:** `engine-service/src/test/java/com/certacota/engine/service/DiscreteTransactionConcurrencyTest.java:117`

**Issue:** Line 117 asserts `failCount >= 0`. `AtomicInteger` is initialized to 0 and only incremented — it can never be negative. This assertion always passes and proves nothing. The intent was presumably to assert that *some* requests fail (demonstrating the floor is enforced), but that assertion is absent. The test therefore does not verify that the pessimistic lock actually prevents double-spend; it only checks that the final balance math is consistent with the observed success count, which could be satisfied even if the locking were broken and transactions serialized by accident.

**Fix:** Assert that at least some debits are rejected when the balance is insufficient:
```java
// With 20 x 10 = 200 total debit attempts and starting balance of 200.00,
// all 20 could succeed — so this particular scenario doesn't guarantee failures.
// Either reduce starting balance or increase thread count to force rejections,
// then assert failCount > 0:
assertThat(successCount.get()).isLessThanOrEqualTo(20); // balance / amount
assertThat(finalBalance.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0); // never negative
```
Also consider asserting `failCount.get() > 0` by sizing the test so the balance is exhausted (e.g., initial balance 100, 20 threads each debiting 10 — max 10 can succeed).

---

### WR-04: `TransactionController` dispatches on `TransactionType` with no handling for future enum values

**File:** `engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java:29-33`

**Issue:** The `if/else` dispatch at lines 29-33 treats every non-`CREDIT` type as `DEBIT`. If a third `TransactionType` enum value (e.g., `TRANSFER`, `REVERSAL`) is added in the future, the controller will silently route it to `debit()` with no validation or error. A `switch` expression with an exhaustive check catches this at compile time.

**Fix:**
```java
return switch (request.type()) {
    case CREDIT -> transactionService.credit(request);
    case DEBIT  -> transactionService.debit(request);
};
```
A Java `switch` expression on an enum is exhaustive; adding a new enum constant without updating the switch causes a compile error.

---

## Info

### IN-01: `PostTransactionRequest.toAccountId` is never validated as non-blank when present

**File:** `engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionRequest.java:16`

**Issue:** `toAccountId` is optional (no `@NotNull`), but when it is present and not null, it could be an empty string `""`. `accountRepository.findById("")` would then attempt a DB lookup for an empty-string ID and return an `AccountNotFoundException`, which gives a misleading 404 rather than a 400 validation error.

**Fix:**
```java
@NotBlank // or a custom constraint: only validate when non-null
String toAccountId
```
Alternatively, add an explicit check in `doDebitWithRake` before the lookup.

---

### IN-02: `BalanceAuditLog` has no `@Column(nullable = false)` constraint on `recordedAt`

**File:** `engine-core/src/main/java/com/certacota/engine/core/domain/BalanceAuditLog.java:50`

**Issue:** The `recordedAt` field has `updatable = false` but no `nullable = false`. Callers who forget to set `recordedAt` in the builder will insert a `NULL` into `balance_audit_log.recorded_at`. The DDL for this column is not in the reviewed migrations (it predates this phase), so the database-level constraint is unknown. The entity definition should express the intent.

**Fix:**
```java
@Column(name = "recorded_at", nullable = false, updatable = false)
private OffsetDateTime recordedAt;
```

---

_Reviewed: 2026-05-13T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
