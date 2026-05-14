---
phase: 03-streaming-transactions
verified: 2026-05-14T12:00:00Z
status: gaps_found
score: 8/13 must-haves verified
overrides_applied: 0
gaps:
  - truth: "POST /api/v1/streams/{streamId}/stop returns 200 with settledAmount computed correctly; Postgres row shows status SETTLED"
    status: failed
    reason: "CR-05: stopStream uses ifPresent on streamingTransactionRepository.findByStreamId at line 185. If no DB row exists the settlement block (status update to SETTLED, audit log) is silently skipped. The account debit at line 179 has already been applied, so balance is debited with no matching SETTLED row and no audit entry — caller receives a 200 with a settledAmount in the body for a partially-completed operation. CR-09: clampToAvailableBalance (StreamSettlementCalculator line 41-44) computes available = accountBalance.subtract(effectiveFloor) with no max(ZERO) guard. When committed balance is below floor (reachable via concurrent discrete debits during an active stream), available is negative, projected.min(available) returns the negative value, and account.debit(negative) credits the account instead of debiting it."
    artifacts:
      - path: "engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java"
        issue: "Line 185: streamingTransactionRepository.findByStreamId(streamId).ifPresent(...) must be .orElseThrow(() -> new StreamNotFoundException(streamId)) to prevent silent no-op settlement"
      - path: "engine-core/src/main/java/com/certacota/engine/core/domain/StreamSettlementCalculator.java"
        issue: "Line 42: available = accountBalance.subtract(effectiveFloor) missing .max(BigDecimal.ZERO) — negative available propagates to account.debit() as a credit"
    missing:
      - "StreamSettlementCalculator.clampToAvailableBalance must clamp available to zero minimum: available = accountBalance.subtract(effectiveFloor).max(BigDecimal.ZERO)"
      - "stopStream must throw StreamNotFoundException when no DB row found instead of silently skipping settlement"

  - truth: "Redis account-streams:{accountId} Set contains streamId after start and is cleared after stop; in-memory and durable state are never permanently divergent (STR-05)"
    status: failed
    reason: "CR-02: streamRegistry.remove() at line 213 and autoTerminationScheduler.cancel() at line 212 are called inside the @Transactional method after streamingTransactionRepository.save(settled). If Postgres commit fails after the Redis removal has executed (Redis operations are not transactional), the stream is removed from Redis but remains ACTIVE in Postgres. The account debit is rolled back but Redis state is gone — stream cannot be stopped via normal path, only by fallback sweep. This is a permanent split-brain window on any Postgres commit failure."
    artifacts:
      - path: "engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java"
        issue: "Lines 212-213: autoTerminationScheduler.cancel and streamRegistry.remove are called inside @Transactional before Postgres commit. Must be moved to @TransactionalEventListener(phase = AFTER_COMMIT) to prevent split-brain on rollback."
    missing:
      - "Publish a StreamSettledEvent inside the @Transactional method; move Redis removal and scheduler cancel to an AFTER_COMMIT TransactionalEventListener"

  - truth: "StreamingConcurrencyTest fires one active stream + N concurrent discrete debits against same account; final Postgres balance = correct math result, no balance below floor, no double-spend (STR-04)"
    status: failed
    reason: "WR-06 from review: test asserts finalBalance >= 0 only (line 139). With initial balance 1000, 10 debits of 50 each, and a stream rate of 0.001/sec running for a few seconds, the balance could be anywhere between 500 and 1000 and still pass. A regression that allows double-spend would not be caught. Additionally: the review claimed the debit endpoint returns 200 making successCount always 0. This reviewer verified: AccountController line 56 annotates /{accountId}/debit with @ResponseStatus(HttpStatus.CREATED) — so the 201 check at line 109 of StreamingConcurrencyTest IS correct for the debit endpoint. However the core assertion weakness (finalBalance >= 0 only, without checking that successCount * 50 was actually deducted) remains — the test does not verify the no-double-spend invariant quantitatively."
    artifacts:
      - path: "engine-service/src/test/java/com/certacota/engine/service/StreamingConcurrencyTest.java"
        issue: "Line 139: assertThat(finalBalance.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0) does not verify that the deducted amount equals successCount * debitAmount + settledStreamAmount. A double-spend regression passes this assertion."
    missing:
      - "Add assertion: finalBalance <= initialBalance (1000 - at least some amount deducted)"
      - "Add assertion: successCount > 0 to prove debit endpoint was actually called successfully"
      - "Add assertion: (1000 - finalBalance) is approximately (successCount * 50 + settledAmount) within a reasonable tolerance"
---

# Phase 3: Streaming Transactions Verification Report

**Phase Goal:** Rate-based streaming drains start, run in-memory, and settle to Postgres using mathematical projection; forward balance estimation is correct across all concurrent in-flight streams; minimum amount and increment billing parameters are enforced on settlement; the engine auto-terminates streams at balance exhaustion using a priority-queue scheduler
**Verified:** 2026-05-14T12:00:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | POST /api/v1/streams returns 201 with streamId, accountId, ratePerSecond, startedAt | ✓ VERIFIED | StreamController.java POST / returns 201; StreamingServiceImpl.startStream fully wired through pessimistic lock → save → Redis register |
| 2 | POST /api/v1/streams/{streamId}/stop returns 200 with correct settledAmount; Postgres row status=SETTLED | ✗ FAILED | CR-05: ifPresent at line 185 silently skips DB row update if no row found. CR-09: clampToAvailableBalance returns negative when balance < floor — debit becomes a credit |
| 3 | GET /api/v1/accounts/{accountId}/estimated-balance returns estimatedBalance, committedBalance, estimatedAt, estimatedDrainAt without a second Postgres read | ✓ VERIFIED | estimateBalance reads Redis for active streams; computes projected sums; returns EstimatedBalanceResponse with all four fields; estimatedDrainAt computation correct |
| 4 | Postgres streaming_transactions row inserted on start (status=ACTIVE) and updated on stop (status=SETTLED) | ✓ VERIFIED (partial) | Insert confirmed (startStream line 114). Stop uses ifPresent (CR-05) — if row is present settlement works, but absence silently no-ops |
| 5 | Redis stream:{streamId} hash exists after start; removed after stop | ✓ VERIFIED (with caveat) | register() confirmed in startStream line 145. remove() at stop line 213 confirmed. CR-02: removal inside @Transactional creates split-brain risk on Postgres rollback |
| 6 | STR-05: in-memory and durable state are never permanently divergent | ✗ FAILED | CR-02: Redis removal inside @Transactional before Postgres commit — rollback removes Redis entry while Postgres row stays ACTIVE. Fallback sweep is recovery only, not prevention |
| 7 | minimumAmount enforcement: early stop charges minimumAmount; ignoreMinimum=true charges actual elapsed | ✓ VERIFIED | StreamSettlementCalculator.computeSettledAmount lines 23-27 implement minimumAmount logic. Cucumber streaming-minimum-amount.feature reported GREEN in test results |
| 8 | increment billing: settledAmount = floor(rate × elapsed / increment) × increment | ✓ VERIFIED | StreamSettlementCalculator lines 16-21 implement increment floor division using BigDecimal RoundingMode.FLOOR. streaming-increment.feature reported GREEN |
| 9 | Auto-termination: streams auto-terminate at balance exhaustion with reason=balance_exhaustion | ✓ VERIFIED | AutoTerminationScheduler virtual consumer thread calls streamingService.autoTerminate(streamId); autoTerminate delegates to stopStream(streamId, new StopStreamRequest(true, "balance_exhaustion")); streaming-auto-termination.feature reported GREEN |
| 10 | Forward balance estimation is correct across all concurrent in-flight streams (BAL-02) | ✓ VERIFIED | estimateBalance reads streamRegistry.getActiveStreams and subtracts totalProjected from committed balance; computeProjection uses wall-clock millis correctly |
| 11 | BigDecimal (not floating-point) used for all rate arithmetic (STR-06) | ✓ VERIFIED | StreamSettlementCalculator has no float/double literals; RoundingMode.DOWN used throughout; ArithmeticTest verifies 5 specific BigDecimal computation results |
| 12 | STR-04: Concurrent streaming + discrete debits produce correct final balance; no double-spend | ✗ FAILED | StreamingConcurrencyTest assertion (finalBalance >= 0) does not quantitatively verify no-double-spend. CR-09 clamp bug creates potential for credits when balance < floor under concurrency |
| 13 | Auto-termination uses priority-queue scheduler (Redisson DelayedQueue), not constant polling (AUTO-02) | ✓ VERIFIED | AutoTerminationScheduler uses RBlockingDeque/RDelayedQueue from Redisson; enqueue called after startStream with calculated exhaustion delay; FallbackSweepJob is secondary safety net |

**Score:** 8/13 truths verified (3 failed)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql` | streaming_transactions DDL | ✓ VERIFIED | SUMMARY reports chk_str_rate, uq_stream_id, fk_str_account present |
| `engine-service/src/main/resources/db/migration/V8__create_shedlock.sql` | shedlock DDL | ✓ VERIFIED | SUMMARY reports correct 4-column schema |
| `engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql` | audit_archive schema + mirror table | ✓ VERIFIED | SUMMARY reports audit_archive schema with balance_audit_log |
| `engine-core/src/main/java/com/certacota/engine/core/domain/StreamSettlementCalculator.java` | Pure-Java settlement arithmetic | ✓ VERIFIED (with bugs) | Exists, no Spring imports. computeSettledAmount, computeProjection, clampToAvailableBalance all present. CR-09: clampToAvailableBalance has negative-value bug |
| `engine-spring/src/main/java/com/certacota/engine/spring/redis/RedisStreamRegistry.java` | Redis-backed StreamRegistry | ✓ VERIFIED | Full implementation with StringRedisTemplate; all 5 interface methods implemented |
| `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java` | StreamingService implementation | ✓ VERIFIED (with bugs) | CR-05 ifPresent bug in stopStream. CR-02 Redis removal inside @Transactional |
| `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java` | AutoConfiguration wiring | ✓ VERIFIED | Beans for StreamRegistry, StreamingService, LockProvider, AutoTerminationScheduler, FallbackSweepJob, AuditArchivalJob present |
| `engine-service/src/main/java/com/certacota/engine/service/controller/StreamController.java` | POST /api/v1/streams + stop endpoint | ✓ VERIFIED | Two POST endpoints wired to StreamingService |
| `engine-service/src/main/java/com/certacota/engine/service/controller/EstimationController.java` | GET estimated-balance endpoint | ✓ VERIFIED | Maps to estimateBalance; returns EstimatedBalanceResponse |
| `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AutoTerminationScheduler.java` | Redisson DelayedQueue scheduler | ✓ VERIFIED | RBlockingDeque consumer, enqueue/cancel methods, virtual thread |
| `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/FallbackSweepJob.java` | ShedLock-guarded fallback sweep | ✓ VERIFIED | @SchedulerLock, LockAssert.assertLocked() first line, wall-clock elapsed |
| `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AuditArchivalJob.java` | OPS-02 audit archival | ✓ VERIFIED (with bugs) | INSERT before DELETE invariant present. CR-03: INSERT and DELETE are two independent non-transactional JDBC calls — data loss window on JVM crash between them |
| `engine-service/src/test/java/com/certacota/engine/service/StreamingConcurrencyTest.java` | STR-04 concurrency test | ✗ STUB | File exists and compiles but assertion only checks finalBalance >= 0, which does not verify no-double-spend. Review WR-06 confirmed |
| `engine-spring/src/test/java/com/certacota/engine/spring/ArithmeticTest.java` | STR-06 arithmetic unit test | ✓ VERIFIED | 5 BigDecimal test cases calling StreamSettlementCalculator static methods; 5 passed per SUMMARY |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| StreamController.startStream | StreamingServiceImpl.startStream | StreamingService injection | ✓ WIRED | Confirmed in controller + service |
| StreamingServiceImpl.startStream | AccountRepository.findWithLock | pessimistic write lock | ✓ WIRED | Line 66: findWithLock called before any write |
| StreamingServiceImpl.startStream | RedisStreamRegistry.register | StreamRegistry injection | ✓ WIRED | Line 145: streamRegistry.register(state) |
| StreamingServiceImpl.stopStream | account.debit(settledAmount) | Account.debit() inside @Transactional | ✓ WIRED | Line 179: account.debit(clampedAmount) |
| StreamingServiceImpl.estimateBalance | RedisStreamRegistry.getActiveStreams | reads from Redis without Postgres per-stream read | ✓ WIRED | Line 233: streamRegistry.getActiveStreams(accountId) |
| streamRegistry.remove | Postgres commit (AFTER_COMMIT) | @TransactionalEventListener | ✗ NOT_WIRED | CR-02: removal is inside @Transactional synchronously, not after commit |
| streamingTransactionRepository.findByStreamId (stopStream) | orElseThrow settlement | must throw on missing row | ✗ NOT_WIRED | CR-05: uses ifPresent — silently skips settlement if row absent |
| AutoTerminationScheduler.enqueue | startStream (after Postgres commit) | called after register() | ✓ WIRED | Line 152: enqueue called after register |
| AutoTerminationScheduler consumer | StreamingServiceImpl.autoTerminate | RBlockingDeque.take() delivery | ✓ WIRED | Consumer thread calls streamingService.autoTerminate(sid) |
| FallbackSweepJob | StreamingServiceImpl.autoTerminate | @SchedulerLock-guarded @Scheduled | ✓ WIRED | FallbackSweepJob calls streamingService.autoTerminate |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|--------------------|--------|
| StreamingServiceImpl.stopStream | clampedAmount | StreamSettlementCalculator.clampToAvailableBalance | Yes, but CR-09: available can be negative | ✗ HOLLOW — clamp returns negative value when balance < floor |
| StreamingServiceImpl.estimateBalance | estimatedBalance | streamRegistry.getActiveStreams → computeProjection | Yes — Redis streams summed with wall-clock elapsed | ✓ FLOWING |
| StreamingServiceImpl.startStream | txn (StreamingTransaction) | streamingTransactionRepository.save | Yes — Postgres write with real data | ✓ FLOWING |
| AuditArchivalJob.runArchival | rows copied/deleted | jdbcTemplate.update (parameterized) | Yes — but two non-transactional calls, crash window | ⚠️ STATIC risk on partial execution |

### Behavioral Spot-Checks

Step 7b: SKIPPED — no runnable server entry point in this environment. Cucumber test results from SUMMARY provide behavioral evidence.

Behavioral results from SUMMARY test reports:
| Behavior | Evidence | Status |
|----------|----------|--------|
| streaming-start.feature all scenarios GREEN | 03-04 SUMMARY: 39 tests, 0 failures | ✓ PASS |
| streaming-stop.feature all scenarios GREEN | 03-04 SUMMARY: 39 tests, 0 failures | ✓ PASS (CR-05 not hit in normal-flow test; row always exists in happy path) |
| streaming-estimation.feature all scenarios GREEN | 03-04 SUMMARY: 39 tests, 0 failures | ✓ PASS |
| streaming-minimum-amount.feature all scenarios GREEN | 03-04 SUMMARY: 39 tests, 0 failures | ✓ PASS |
| streaming-increment.feature all scenarios GREEN | 03-04 SUMMARY: 39 tests, 0 failures | ✓ PASS |
| streaming-auto-termination.feature all scenarios GREEN | 03-04 SUMMARY: 39 tests, 0 failures | ✓ PASS |
| ArithmeticTest 5 cases | 03-04 SUMMARY: 5 tests passed engine-spring | ✓ PASS |
| StreamingConcurrencyTest | 03-04 SUMMARY: 1 test PASSED | ✓ PASS (but assertion too weak per WR-06) |

### Probe Execution

No probe scripts declared or conventional probe files found for this phase.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| STR-01 | 03-01, 03-02, 03-04 | Caller can start a streaming drain specifying rate | ✓ SATISFIED | POST /api/v1/streams implemented; Cucumber streaming-start.feature GREEN |
| STR-02 | 03-01, 03-02, 03-04 | Stop stream settles via mathematical projection (rate × elapsed), not tick accumulation | ✓ SATISFIED | computeElapsedSeconds uses nanoTime or wall-clock; BigDecimal multiply; streaming-stop.feature GREEN. Note: nanoTime path is effectively dead (CR-04) but wall-clock path is correct |
| STR-03 | 03-01, 03-02, 03-04 | Active streaming state in-memory; estimation without DB read per query | ✓ SATISFIED | RedisStreamRegistry holds live state; estimateBalance reads only Redis + one Postgres balance read |
| STR-04 | 03-01, 03-04 | Concurrent streaming + discrete transactions against same balance correct | ✗ BLOCKED | StreamingConcurrencyTest assertion (finalBalance >= 0) does not quantitatively verify no-double-spend; CR-09 clamp bug creates potential negative-debit under concurrency |
| STR-05 | 03-01, 03-02, 03-04 | Settles atomically to Postgres on stop; in-memory and durable state never permanently divergent | ✗ BLOCKED | CR-02: Redis removal inside @Transactional — diverges permanently on Postgres commit failure |
| STR-06 | 03-01, 03-02, 03-04 | BigDecimal for all rate arithmetic | ✓ SATISFIED | StreamSettlementCalculator has no float/double; ArithmeticTest verifies |
| STR-07 | 03-01, 03-03, 03-04 | minimumAmount enforced on stop; ignoreMinimum flag works | ✓ SATISFIED | computeSettledAmount implements minimum check correctly; Cucumber GREEN |
| STR-08 | 03-01, 03-03, 03-04 | increment parameter rounds down to nearest complete increment | ✓ SATISFIED | computeSettledAmount implements floor division; streaming-increment.feature GREEN |
| STR-09 | 03-01, 03-03, 03-04 | Auto-termination triggers when balance drops below one increment | ✓ SATISFIED | AUTO-01 implementation covers this; balance floor check triggers auto-terminate |
| AUTO-01 | 03-01, 03-03, 03-04 | Auto-terminates when estimated balance reaches floor; minimum waived | ✓ SATISFIED | autoTerminate calls stopStream with ignoreMinimum=true; streaming-auto-termination.feature GREEN |
| AUTO-02 | 03-01, 03-03, 03-04 | Schedules termination using priority queue keyed by exhaustion time, not polling | ✓ SATISFIED | Redisson RDelayedQueue used; calculateExhaustionDelayMillis computes precise delay |
| AUTO-03 | 03-01, 03-03, 03-04 | Auto-termination emits reason=balance_exhaustion | ✓ SATISFIED | stopStream with reason="balance_exhaustion"; streaming-auto-termination.feature GREEN |
| BAL-02 | 03-01, 03-02, 03-04 | Forward-estimated balance with in-flight streaming transactions | ✓ SATISFIED | estimateBalance returns estimatedBalance, committedBalance, estimatedAt, estimatedDrainAt |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| StreamSettlementCalculator.java | 41-44 | `clampToAvailableBalance` returns negative when `accountBalance < effectiveFloor` | BLOCKER | Account credited instead of debited when balance already below floor — incorrect settlement amount |
| StreamingServiceImpl.java | 185 | `ifPresent` on `findByStreamId` in `stopStream` — settlement silently skipped on missing row | BLOCKER | 200 returned with settledAmount but no DB update, no audit log, no actual settlement |
| StreamingServiceImpl.java | 212-213 | Redis removal + scheduler cancel inside `@Transactional` before Postgres commit | BLOCKER | Split-brain: stream removed from Redis but Postgres stays ACTIVE on rollback |
| StreamingConcurrencyTest.java | 139 | `finalBalance >= 0` only — no double-spend quantitative assertion | WARNING | STR-04 concurrency test is not a meaningful correctness guard |
| AuditArchivalJob.java | 33-42 | Two non-transactional JDBC calls for INSERT+DELETE with no enclosing transaction | WARNING | Data loss window on JVM crash between archival steps; duplicate archive rows on retry |

No `TBD`, `FIXME`, or `XXX` debt markers found in searched files.

### Human Verification Required

None — all gaps are programmatically observable from the source code.

### Gaps Summary

Three blockers prevent full goal achievement:

**Blocker 1 — clampToAvailableBalance returns negative (CR-09)**
`StreamSettlementCalculator.clampToAvailableBalance` at line 42 computes `available = accountBalance.subtract(effectiveFloor)` with no floor at zero. When the committed balance drops below the effective floor (reachable under concurrent discrete debits hitting the same account while a stream is active), `available` is negative, `projected.min(available)` returns the negative value, and `account.debit(negative)` credits the account. This is a data correctness defect in the core settlement path. The fix is one line: `.max(BigDecimal.ZERO)` on the available computation.

**Blocker 2 — stopStream silently no-ops on missing DB row (CR-05)**
`StreamingServiceImpl.stopStream` at line 185 uses `ifPresent` to wrap the SETTLED row update and audit log write. The account debit at line 179 executes unconditionally, but if no `StreamingTransaction` row exists, the settlement is half-complete: balance is debited but no SETTLED row is written and no audit log is recorded. The caller receives HTTP 200 with `settledAmount` but the DB stays inconsistent. The fix is `orElseThrow(() -> new StreamNotFoundException(streamId))`.

**Blocker 3 — Redis removal inside @Transactional creates split-brain on rollback (CR-02)**
`streamRegistry.remove()` and `autoTerminationScheduler.cancel()` at lines 212-213 execute inside the `@Transactional` boundary of `stopStream`. If `streamingTransactionRepository.save(settled)` at line 200 succeeds but the overall Postgres transaction later rolls back (e.g., constraint violation, network failure on commit), Redis has already removed the stream hash and account-streams set entry. The stream is now invisible to the normal stop path but remains ACTIVE in Postgres. The fallback sweep will eventually recover this, but there is a window — potentially minutes (default 300s sweep interval) — during which the account accrues tokens that cannot be settled through the normal flow. The correct fix is to move Redis removal to a `@TransactionalEventListener(phase = AFTER_COMMIT)` handler.

The Cucumber test suite passes 39/39 because the happy-path scenarios do not trigger any of these edge conditions (no Postgres row is ever absent in normal flow; balance does not go below floor in the tests; Postgres commits always succeed in Testcontainers). The bugs are latent production defects that tests do not exercise.

---

_Verified: 2026-05-14T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
