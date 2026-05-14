---
phase: 03-streaming-transactions
reviewed: 2026-05-14T00:00:00Z
depth: standard
files_reviewed: 23
files_reviewed_list:
  - engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java
  - engine-spring/src/main/java/com/certacota/engine/spring/redis/RedisStreamRegistry.java
  - engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AutoTerminationScheduler.java
  - engine-spring/src/main/java/com/certacota/engine/spring/scheduler/FallbackSweepJob.java
  - engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AuditArchivalJob.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/StreamSettlementCalculator.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java
  - engine-core/src/main/java/com/certacota/engine/core/service/StreamRegistry.java
  - engine-core/src/main/java/com/certacota/engine/core/service/StreamingService.java
  - engine-service/src/main/java/com/certacota/engine/service/controller/StreamController.java
  - engine-service/src/main/java/com/certacota/engine/service/controller/EstimationController.java
  - engine-service/src/main/java/com/certacota/engine/service/controller/GlobalExceptionHandler.java
  - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java
  - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java
  - engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java
  - engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java
  - engine-spring/src/main/java/com/certacota/engine/spring/service/AccountServiceImpl.java
  - engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql
  - engine-service/src/main/resources/db/migration/V8__create_shedlock.sql
  - engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql
  - engine-service/src/test/java/com/certacota/engine/service/StreamingConcurrencyTest.java
  - engine-spring/src/test/java/com/certacota/engine/spring/ArithmeticTest.java
findings:
  critical: 9
  warning: 6
  info: 2
  total: 17
status: fixed
---

# Phase 3: Code Review Report

**Reviewed:** 2026-05-14T00:00:00Z
**Depth:** standard
**Files Reviewed:** 23
**Status:** fixed

## Summary

Phase 3 implements streaming transactions: Redis-backed live state, settlement arithmetic, auto-termination via Redisson delayed queues, ShedLock-guarded sweep and archival jobs, and a startup reconciliation path. The architecture is generally sound but contains nine blocker-level defects concentrated in four areas: idempotency ordering (race window before key creation), settlement atomicity (Redis removal before Postgres commit), AuditArchivalJob data loss (non-transactional INSERT/DELETE pair), Redis `startedAtNanoFromCurrentJvm` heuristic that fires on every JVM restart with false-same-JVM results, and several incorrect-behaviour edge cases.

---

## Critical Issues

### CR-01: Idempotency key stored AFTER Redis registration — duplicate streams possible under concurrent identical keys

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java:114-156`

**Issue:** The idempotency key is written to the database at line 155 only after the streaming transaction row is persisted (line 114-123) and after `streamRegistry.register(state)` (line 145). A second request with the same idempotency key that arrives after the DB row is saved but before the idempotency-key row is committed will pass the idempotency check at line 69-74 (key not found yet), find no ACTIVE stream in Redis (not yet registered), and create a duplicate `StreamingTransaction` row. The `UNIQUE` constraint on `stream_id` will block the second insert, but the caller receives a 500 instead of the correct idempotent 201, and the first stream may be left in an inconsistent state if the second request's transaction partially completed.

The root cause is that `storeIdempotencyKey` is called after all side effects. The idempotency check must guard execution, so the key must be persisted first — or the check and store must be done inside the same atomic unit before any state mutation.

**Fix:**
```java
// Move idempotencyKeyRepository.save() to BEFORE streamingTransactionRepository.save().
// Use an INSERT ... ON CONFLICT DO NOTHING pattern or persist the key as the very
// first write inside the transaction so the unique constraint prevents double execution.

// Minimal fix: store a "pending" key before the stream is created, update with
// response after. Or restructure to:
// 1. BEGIN transaction
// 2. Acquire account lock
// 3. INSERT idempotency_key (will fail fast on duplicate)
// 4. All other writes
// 5. COMMIT
```

---

### CR-02: `streamRegistry.remove()` is called after `accountRepository.save()` but inside the SAME transaction — Redis state is torn on transaction rollback

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java:212-214`

**Issue:** `stopStream` debits the account and saves to Postgres (lines 179-180), then calls `autoTerminationScheduler.cancel(streamId)` and `streamRegistry.remove(streamId, state.accountId())` (lines 212-213) — all inside the `@Transactional` method. If the Postgres transaction rolls back after the Redis removal (e.g., because `streamingTransactionRepository.save(settled)` fails at line 200), the stream is removed from Redis but remains ACTIVE in Postgres. Subsequent calls to `get(streamId)` return empty (Redis is gone), so the stream cannot be stopped via the normal path; it can only be recovered by the fallback sweep. Meanwhile the account balance was not debited (rolled back), so the stream accrues tokens that will never be settled.

Redis operations are not transactional with JPA and cannot be rolled back. The fix is to perform Redis removal only after the Postgres transaction has committed, using a `@TransactionalEventListener(phase = AFTER_COMMIT)` pattern.

**Fix:**
```java
// Publish a domain event inside the @Transactional method:
eventPublisher.publishEvent(new StreamSettledEvent(streamId, state.accountId()));

// Listen after commit (no @Transactional on the listener):
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onStreamSettled(StreamSettledEvent event) {
    autoTerminationScheduler.cancel(event.streamId());
    streamRegistry.remove(event.streamId(), event.accountId());
}
```

---

### CR-03: `AuditArchivalJob` INSERT and DELETE are not in a single transaction — data loss window

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AuditArchivalJob.java:33-42`

**Issue:** The archival job executes a `JdbcTemplate.update()` for the INSERT (line 33) and then another `JdbcTemplate.update()` for the DELETE (line 40) as two independent JDBC calls with no enclosing transaction. If the JVM dies, a network partition occurs, or any exception is thrown between lines 36 and 40, the rows have been archived but NOT deleted from the live table. On the next run, rows that were already archived will be re-inserted into `audit_archive.balance_audit_log`, creating duplicates (the archive table has no primary key or unique constraint — see V9 migration). Worse, the delete step will succeed on that second run, so the duplicates are permanent.

The ShedLock prevents two concurrent executions but does not help with single-node crash-between-steps. The archive table also lacks a primary key, so there is no idempotency guard.

**Fix:**
```java
// Wrap both operations in a @Transactional method, or use JdbcTemplate inside a
// TransactionTemplate:
@Transactional
public void runArchival() {
    assertLocked();
    int archived = jdbcTemplate.update("INSERT INTO audit_archive.balance_audit_log ...", retentionDays);
    int deleted  = jdbcTemplate.update("DELETE FROM balance_audit_log ...", retentionDays);
    ...
}
// AND add a primary key to audit_archive.balance_audit_log (see CR-08).
```

---

### CR-04: `RedisStreamRegistry.startedAtNanoFromCurrentJvm()` always returns `true` after a JVM restart

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/redis/RedisStreamRegistry.java:27,106-108`

**Issue:** `JVM_START_NANO` is captured at class-load time: `System.nanoTime()` at line 27. The `startedAtNanoFromCurrentJvm` helper at lines 106-108 returns `true` if `storedNano >= JVM_START_NANO`. After a JVM restart, `JVM_START_NANO` resets to a small positive value near zero. Every stream that was started by a previous JVM stored a `startedAtNano` value that was large (several seconds after that JVM's epoch). When the new JVM loads, its `JVM_START_NANO` is also a small value. Because stored nanos from a prior JVM are likely much larger than the new JVM's near-zero `JVM_START_NANO`, the condition `storedNano >= JVM_START_NANO` evaluates to `true` for stale cross-JVM streams, incorrectly flagging them as belonging to the current JVM.

This means `StreamingServiceImpl.computeElapsedSeconds` at line 303 will use `System.nanoTime() - state.startedAtNano()` for a stale cross-JVM stream, producing a garbage elapsed value (potentially negative or wildly wrong), causing incorrect settlement amounts on `stopStream`.

In practice the startup reconciliation in `onApplicationReady` calls `StreamState.fromDbRow(txn, 0L)` which always sets `startedAtNanoFromCurrentJvm = false` (line 37 of `StreamState.java`), so reconciled streams are safe. But streams that survived in Redis across a restart (not yet reconciled by startup, e.g., in a race window) will be read by `get()` which calls `StreamState.fromRedis()` which hard-codes `startedAtNanoFromCurrentJvm = false` (line 35 of `StreamState.java`). So at runtime this specific bug is masked — `fromRedis` always returns `false`. However the `startedAtNanoFromCurrentJvm` field stored in Redis is never `true` anyway (register() never stores it), and the static method is dead code that misleads future readers about correctness. The field in `StreamState` is only set `true` at construction time in `StreamingServiceImpl.startStream` (line 141), but that live object is never the one consulted at stop time — `get(streamId)` always reads from Redis and returns `false`. So `computeElapsedSeconds` always takes the millis branch, making `System.nanoTime()` precision unused even for same-JVM streams.

This is architecturally broken: the design intent (use nanoTime for same-JVM precision) is entirely defeated. Any stream started and stopped on the same JVM will compute elapsed time using wall-clock millis, not nanos, because the Redis-deserialized `StreamState` always has `startedAtNanoFromCurrentJvm = false`.

**Fix:** Either (a) remove the nanoTime precision path entirely and commit to wall-clock millis for all elapsed computation (simpler, correct), or (b) store a JVM instance identifier in Redis alongside `startedAtNano` and compare identifiers rather than comparing nano values numerically.

---

### CR-05: `stopStream` silently skips settlement if no `StreamingTransaction` row exists in Postgres

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java:185-210`

**Issue:** Line 185 uses `streamingTransactionRepository.findByStreamId(streamId).ifPresent(txn -> { ... })`. If no row is found (e.g., due to a startup edge case, a bug in startStream, or a manual DB intervention), the entire settlement block — the status update to SETTLED, the audit log entry, and the account debit — is skipped silently. The method returns a `StopStreamResponse` (line 215) as if settlement succeeded, but the account balance was neither debited nor audited, and the stream row remains absent. The caller receives HTTP 200 with `settledAmount` in the body, but nothing was actually settled.

**Fix:**
```java
StreamingTransaction txn = streamingTransactionRepository.findByStreamId(streamId)
    .orElseThrow(() -> new StreamNotFoundException(streamId));
// Then proceed with settlement unconditionally.
```

---

### CR-06: Transfer floor check uses `account.getBalance()` directly, ignoring active streams

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:205-211`

**Issue:** The `transfer` method computes the floor violation check at line 205-211 using `fromAccount.getBalance().subtract(request.amount())` — the raw committed balance with no deduction for active streaming projections. Compare this to the correct `debit` path (lines 136-143) which computes `estimatedBalance = account.getBalance().subtract(totalProjected)` before checking the floor. A transfer from an account with active streams can therefore succeed even when the resulting estimated balance would be below the floor, allowing double-spend against the streaming projections. This is the same correctness invariant as D-17 that was correctly implemented for debit but not for transfer.

**Fix:**
```java
List<StreamState> activeStreams = getActiveStreamsWithFallback(request.accountId());
BigDecimal totalProjected = activeStreams.stream()
    .map(StreamSettlementCalculator::computeProjection)
    .reduce(BigDecimal.ZERO, BigDecimal::add);
BigDecimal estimatedBalance = fromAccount.getBalance().subtract(totalProjected);
BigDecimal resultingBalance = estimatedBalance.subtract(request.amount());
if (resultingBalance.compareTo(effectiveFloor) < 0) { ... }
```

---

### CR-07: `AutoTerminationScheduler` uses `delayedQueue`/`destinationQueue` before `onApplicationEvent` fires — NPE if `enqueue`/`cancel` called early

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AutoTerminationScheduler.java:27-44`

**Issue:** `destinationQueue` and `delayedQueue` are instance fields initialized to `null` (lines 27-28). They are assigned in `onApplicationEvent` (lines 32-33), which fires on `ApplicationReadyEvent`. However, `StreamingServiceImpl.onApplicationReady()` is also an `ApplicationReadyEvent` listener and calls `autoTerminationScheduler.enqueue()` (line 292 of `StreamingServiceImpl`). Spring does not guarantee listener ordering within the same event unless `@Order` is used. If `StreamingServiceImpl.onApplicationReady()` fires before `AutoTerminationScheduler.onApplicationEvent()`, `delayedQueue.offer()` at line 40 of `AutoTerminationScheduler` will throw a `NullPointerException`.

**Fix:**
```java
// Add @Order to guarantee AutoTerminationScheduler initializes first:
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AutoTerminationScheduler implements ApplicationListener<ApplicationReadyEvent> { ... }

// And in StreamingServiceImpl:
@EventListener(ApplicationReadyEvent.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public void onApplicationReady() { ... }
```

---

### CR-08: `audit_archive.balance_audit_log` has no primary key — allows silent duplicate rows on archival retry

**File:** `engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql:3-13`

**Issue:** The archive table is created without a primary key or unique constraint on `id`. When the archival job runs twice for overlapping date ranges (possible if `retentionDays` changes, a job is retried, or the INSERT-before-DELETE non-transactional gap described in CR-03 is hit), the same `id` values are inserted a second time without any database-level rejection. The archive silently accumulates duplicates, corrupting audit history. The archive table also omits `NOT NULL` on `id` even though `id` is conceptually the same primary key as in the source table.

**Fix:**
```sql
CREATE TABLE audit_archive.balance_audit_log (
    id              BIGINT          NOT NULL,
    ...
    CONSTRAINT pk_audit_archive PRIMARY KEY (id)
);
```

---

### CR-09: `clampToAvailableBalance` can return a negative value when account balance is below floor

**File:** `engine-core/src/main/java/com/certacota/engine/core/domain/StreamSettlementCalculator.java:41-44`

**Issue:** `clampToAvailableBalance` computes `available = accountBalance.subtract(effectiveFloor)`. If `accountBalance` is already below `effectiveFloor` (which can happen after concurrent discrete debits posted during an active stream), `available` is negative. `projected.min(available)` then returns the negative value, and the calling code in `stopStream` calls `account.debit(clampedAmount)` with a negative number — effectively crediting the account instead of debiting it. The settlement audit log will record a negative `amount`.

**Fix:**
```java
public static BigDecimal clampToAvailableBalance(
        BigDecimal projected, BigDecimal accountBalance, BigDecimal effectiveFloor) {
    BigDecimal available = accountBalance.subtract(effectiveFloor)
        .max(BigDecimal.ZERO);  // never negative
    return projected.min(available);
}
```

---

## Warnings

### WR-01: `startStream` idempotency check happens AFTER `accountRepository.findWithLock` — account lock held during idempotency DB read

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java:66-74`

**Issue:** The pessimistic write lock on the account is acquired at line 66 before the idempotency check at lines 69-74. For idempotent replays (the common case in retry scenarios), the lock is acquired and immediately released on the next line after finding the key. This unnecessarily serializes concurrent idempotent replays behind the account lock, reducing throughput. The same pattern exists in `TransactionServiceImpl.credit`, `debit`, and `transfer` methods.

**Fix:** Check idempotency before acquiring the account lock. The idempotency key row serves as its own concurrency guard via the unique constraint.

---

### WR-02: `FallbackSweepJob` queries `accountRepository.findById` twice per stream — two separate DB reads with no isolation guarantee between them

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/FallbackSweepJob.java:49-57`

**Issue:** Lines 49-53 call `accountRepository.findById(txn.getAccountId())` to get the effective floor, then lines 55-57 call `accountRepository.findById(txn.getAccountId())` again to get the balance. Between these two reads (within the same scheduler tick but different reads) a concurrent credit could change the balance. More critically, the sweep method has no `@Transactional` annotation, so these two reads are in separate transactions with no repeatable-read guarantee. The `effectiveFloor` and `balance` could reflect different account states, producing an incorrect exhaustion decision (e.g., credit arriving between the two reads could hide an actual exhaustion).

**Fix:** Load the account once and reuse the result, optionally inside a read-only transaction.

---

### WR-03: `calculateExhaustionDelayMillis` multiplies seconds by 1000 before calling `longValue()` — precision loss for large delays

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AutoTerminationScheduler.java:61-64`

**Issue:** Line 62 computes `timeToExhaustionSeconds` (already in the wrong unit — the variable name says seconds but the multiply by 1000 is done inline on the same expression, producing milliseconds), then calls `.multiply(BigDecimal.valueOf(1000))` (line 63) before `.longValue()` (line 64). The result is in milliseconds after the multiply, but intermediate rounding via `divide(..., 3, RoundingMode.DOWN)` truncates to 3 decimal places of seconds, meaning the delay is accurate only to the nearest millisecond. For a stream with a very low rate (e.g., 0.001 tokens/second and 10,000 token balance), the true delay is 10,000,000 seconds = ~115 days. `BigDecimal.divide` with scale 3 produces `10000000.000`, multiply by 1000 = `10000000000.000`, `longValue()` = `10000000000` milliseconds — this is within `Long.MAX_VALUE` and is fine. However the `max(BigDecimal.ZERO)` on line 64 is applied BEFORE `longValue()`, which is correct. The naming confusion between seconds and milliseconds in the intermediate variable is a latent readability/maintenance hazard.

**Fix:** Rename the intermediate variable, or restructure the computation to make the unit clear:
```java
BigDecimal availableTokens = estimatedBalance.subtract(effectiveFloor);
BigDecimal secondsToExhaustion = availableTokens.divide(ratePerSecond, 3, RoundingMode.DOWN);
long delayMillis = secondsToExhaustion.max(BigDecimal.ZERO)
    .multiply(BigDecimal.valueOf(1000L))
    .longValue();
return delayMillis;
```

---

### WR-04: `StreamState.fromDbRow` synthetic `startedAtNano` value is silently wrong under clock skew

**File:** `engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java:41-57`

**Issue:** `fromDbRow` computes `startedAtNano` by sampling `System.currentTimeMillis()` and `System.nanoTime()` at lines 42-43, computing `elapsedSinceStartMillis` at line 44, then back-computing `startedAtNano = currentNano - (elapsedSinceStartMillis * 1_000_000L)` at line 45. This correctly reconstructs a synthetic nano start for the current JVM. However, the field `startedAtNanoFromCurrentJvm` is set to `false` at line 56, so `computeElapsedSeconds` will always use the wall-clock millis path for DB-reconciled streams — making the synthetic nano computation at lines 42-45 completely dead code. The `jvmStartNano` parameter is accepted but never used (line 41). This is wasted computation and dead parameter.

**Fix:** Either remove the nano reconstruction and the `jvmStartNano` parameter (leaving only wall-clock millis), or set `startedAtNanoFromCurrentJvm = true` in `fromDbRow` to actually use the synthetic nano value.

---

### WR-05: `getActiveStreamsWithFallback` in `TransactionServiceImpl` catches `DataAccessResourceFailureException` but `RedisStreamRegistry` throws `RedisUnavailableException`

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:289-299`

**Issue:** `getActiveStreamsWithFallback` catches `DataAccessResourceFailureException` at line 292. However, `RedisStreamRegistry.getActiveStreams()` catches `RedisConnectionFailureException` and does NOT re-throw it — it returns an empty list instead (lines 90-93 of `RedisStreamRegistry`). So `DataAccessResourceFailureException` would only be thrown if a Redis operation fails with a Spring Data exception that is NOT `RedisConnectionFailureException` (a subset). The fallback logic in `TransactionServiceImpl` is therefore dead in the normal Redis-failure case; the swallowed exception in `getActiveStreams` means the debit proceeds on an empty stream list rather than triggering the guard. If Redis is down and there are active streams, the floor protection is silently bypassed rather than returning 503.

This is inconsistent with how `hasActiveStreams` (used in `AccountServiceImpl.closeAccount`) handles the same failure — it throws `RedisUnavailableException` which bubbles up to 503. The debit path should have the same safety guarantee.

**Fix:** `RedisStreamRegistry.getActiveStreams()` should throw `RedisUnavailableException` on connection failure (same as `register`, `get`, and `hasActiveStreams`), not swallow it. The callers that need fallback-to-empty behavior should opt in explicitly.

---

### WR-06: `StreamingConcurrencyTest` asserts `finalBalance >= 0` but never asserts balance integrity relative to debits and stream settlement

**File:** `engine-service/src/test/java/com/certacota/engine/service/StreamingConcurrencyTest.java:139`

**Issue:** The test's core invariant assertion at line 139 only checks that `finalBalance >= 0`. With an initial balance of 1000, 10 debits of 50 each (total 500 if all succeed), and a stream rate of 0.001/second running for a few seconds (negligible settlement), the balance could be anywhere between 500 and 1000 after the test and still pass. The assertion does not verify that at most `floor(1000 / 50) = 20` debits could have succeeded (only 10 attempted), and does not confirm that exactly the number of successful debits was deducted. A regression that allows all 10 debits to double-apply would still pass this test. Additionally, `successCount` is checked for all being 201, but the status code check at line 109 expects `201` — which is correct for the accounts endpoint — but the debit endpoint returns `200` (there is no `@ResponseStatus` on the debit endpoint in `TransactionController`), so `successCount` will always be 0 regardless of success, and `rejectedCount` will always be 10, making this test a no-op for concurrency detection.

**Fix:** Assert that `successCount > 0`, that `successCount * 50 + settledStreamAmount` approximately equals `1000 - finalBalance`, and check for the correct HTTP status code for the debit endpoint.

---

## Info

### IN-01: `@Lazy @Autowired` field injection pattern mixed with `@RequiredArgsConstructor` constructor injection

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java:58-60`
**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:59-61`

**Issue:** Both services use `@RequiredArgsConstructor` (constructor injection via Lombok) for all dependencies, then break the pattern with `@Lazy @Autowired` field injection for `AutoTerminationScheduler`. This is inconsistent. The `@Lazy` is necessary to break the circular dependency (`StreamingServiceImpl` → `AutoTerminationScheduler` → `StreamingService` → `StreamingServiceImpl`), but field injection makes this implicit.

The `StreamingAutoConfiguration` bean factory method already handles the `@Lazy` resolution for the autoconfigure path (line 54 of `StreamingAutoConfiguration`). For `@Service`-annotated classes discovered by component scanning, a `@Lazy` constructor parameter would be cleaner: add a dedicated constructor parameter annotated `@Lazy` rather than mixing field injection.

**Fix:** Use constructor injection with `@Lazy` on the parameter, or document why field injection is required here.

---

### IN-02: `shedlock` table uses `TIMESTAMP` (no timezone) — may cause lock expiry errors under DST or timezone changes

**File:** `engine-service/src/main/resources/db/migration/V8__create_shedlock.sql:3-4`

**Issue:** `lock_until` and `locked_at` are defined as `TIMESTAMP` (without timezone). The ShedLock documentation recommends `TIMESTAMP WITH TIME ZONE` (`TIMESTAMPTZ`) for PostgreSQL to avoid ambiguous comparisons when the server timezone changes (DST transitions or misconfigured DB timezone). The `JdbcTemplateLockProvider` is configured with `.usingDbTime()` in `StreamingAutoConfiguration` which mitigates some of this, but the column type mismatch between application-generated `OffsetDateTime` values and timezone-naive columns remains a latent hazard.

**Fix:**
```sql
lock_until TIMESTAMPTZ NOT NULL,
locked_at  TIMESTAMPTZ NOT NULL,
```

---

_Reviewed: 2026-05-14T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
