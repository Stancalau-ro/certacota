---
phase: 04-tags-rake-thresholds
plan: 04
subsystem: tags
tags: [tags, bulk-settlement, idempotency, shedlock, cleanup-job, cucumber, jpa, scheduler]
dependency_graph:
  requires: [04-03]
  provides: []
  affects: [engine-core, engine-spring, engine-service]
tech_stack:
  added:
    - TagTtlCleanupJob ShedLock-guarded @Scheduled cleanup of stale tag_committed_totals rows
    - TokenEngineProperties.TagProperties inner class with ttlHours=24 and cleanupCron=0 0 3 * * *
    - TagTtlCleanupJobIT standalone Testcontainer integration test
  patterns:
    - Pending-first idempotency with BULK_END_BY_TAG operation key (mirrors startStream lines 81-87)
    - All-or-nothing @Transactional bulk settlement: inner stopStream propagation REQUIRED shares one commit
    - doCleanup() package-private split so IT tests bypass @SchedulerLock assertLocked() guard
    - Silent skip of SETTLED/ERROR streams with skippedCount in response (D-20)
key_files:
  created:
    - engine-spring/src/main/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJob.java
    - engine-spring/src/test/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJobIT.java
  modified:
    - engine-spring/src/main/java/com/certacota/engine/spring/service/TagServiceImpl.java
    - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TagAutoConfiguration.java
    - engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java
    - engine-service/src/test/resources/features/end-by-tag.feature
decisions:
  - "All-or-nothing semantics for TAG-02: no try/catch around inner stopStream() inside endByTag loop — exception propagates, rolling back entire batch including pending idempotency key for clean retry"
  - "doCleanup() package-private split in TagTtlCleanupJob: IT calls doCleanup() directly to bypass assertLocked(); runCleanup() delegates to doCleanup() after assertLocked() for production use"
  - "endByTag uses ignoreMinimum=false in StopStreamRequest: end-by-tag is client-initiated stop, not auto-termination, so minimumAmount semantics apply normally per STR-07"
  - "TagAutoConfiguration updated in this plan (not a separate plan): TagService bean factory gains 4 new collaborators required by endByTag; TagTtlCleanupJob bean added in same class"
requirements-completed: [TAG-02, TAG-05]
duration: ~15 min
completed: "2026-05-14"
---

# Phase 4 Plan 4: End-by-Tag Bulk Settlement + TTL Cleanup Summary

Bulk end-by-tag settlement (TAG-02) replaces the plan-02 UnsupportedOperationException placeholder with an idempotent, atomic, all-or-nothing transaction that settles every ACTIVE stream for a tag and silently skips already-settled ones; TagTtlCleanupJob (TAG-05) prevents tag_committed_totals table growth with a ShedLock-guarded nightly DELETE.

## Performance

- **Duration:** ~15 min
- **Started:** 2026-05-14T17:20:00Z
- **Completed:** 2026-05-14T17:27:00Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- POST /api/v1/tags/{tag}/end atomically settles all ACTIVE streams in a single DB transaction with idempotency (BULK_END_BY_TAG operation key), silent skip of SETTLED/ERROR streams, and default reason "end_by_tag" (TAG-02, D-18, D-19, D-20)
- All 4 end-by-tag.feature scenarios activated and GREEN: bulk settle 3 streams, empty-tag returns 200 settledCount=0, already-settled skipped, idempotency replay returns identical response (D-22)
- TagTtlCleanupJob deletes tag_committed_totals rows where last_activity_at older than ttlHours (default 24h), guarded by ShedLock to prevent concurrent multi-pod runs; TagTtlCleanupJobIT (5 tests) verifies delete-old / retain-fresh / mixed-stale-fresh / default-config behaviors (TAG-05)

## Task Commits

1. **TDD RED: Activate end-by-tag.feature** - `7d5a7e0` (test)
2. **Task 1+2 GREEN: endByTag + TagTtlCleanupJob + TagAutoConfiguration** - `6987379` (feat)
3. **Task 2 IT: TagTtlCleanupJobIT** - `bdad2b1` (test)

## Files Created/Modified

- `engine-spring/.../service/TagServiceImpl.java` — Replaced UnsupportedOperationException with full endByTag: pre-write idempotency check, pending-first BULK_END_BY_TAG key, per-stream stopStream() inside @Transactional, silent skip of SETTLED/ERROR, default reason "end_by_tag"
- `engine-spring/.../scheduler/TagTtlCleanupJob.java` — New: ShedLock-guarded @Scheduled DELETE of stale tag_committed_totals rows; doCleanup() split for IT-testability
- `engine-spring/.../config/TokenEngineProperties.java` — Added TagProperties inner class: ttlHours=24, cleanupCron="0 0 3 * * *"
- `engine-spring/.../autoconfigure/TagAutoConfiguration.java` — Updated tagService() bean to include 4 new collaborators; added tagTtlCleanupJob() @Bean factory
- `engine-service/.../features/end-by-tag.feature` — Removed @Pending from all 4 scenarios
- `engine-spring/.../scheduler/TagTtlCleanupJobIT.java` — New: 5-test IT using standalone PostgreSQL Testcontainer

## Decisions Made

- All-or-nothing @Transactional semantics for TAG-02: no try/catch around inner `stopStream()` — if any stream settlement throws, the whole transaction rolls back including the pending idempotency key, allowing clean retry
- `doCleanup()` package-private split: production `runCleanup()` calls `assertLocked()` then delegates; IT calls `doCleanup()` directly, bypassing the ShedLock guard
- `ignoreMinimum=false` in StopStreamRequest for end-by-tag: client-initiated stop is not auto-termination; minimumAmount semantics apply normally per STR-07
- TagAutoConfiguration updated in this plan for atomicity: tagService() wiring change and tagTtlCleanupJob() registration land in the same commit as TagServiceImpl

## Deviations from Plan

None — plan executed exactly as written. All acceptance criteria met without additional fixes.

## Known Stubs

None. The UnsupportedOperationException placeholder from plan 04-02 has been replaced by the full implementation. All tag service operations are now functional.

## Final Phase 4 Test State

| Test Suite | Tests | Failures |
|------------|-------|---------|
| CucumberTestRunner (engine-service) | 54 | 0 |
| DiscreteTransactionConcurrencyTest | 1 | 0 |
| StreamingConcurrencyTest | 1 | 0 |
| TagAutoConfigurationIT | 2 | 0 |
| ArithmeticTest (engine-spring) | 5 | 0 |
| RedisStreamRegistryTagIT | 4 | 0 |
| StreamingTransactionRakeConstraintIT | 4 | 0 |
| **TagTtlCleanupJobIT (new)** | **5** | **0** |
| MetadataConverterTest (engine-core) | 7 | 0 |
| TagCommittedTotalsTest | 5 | 0 |
| **Total** | **88** | **0** |

All Phase 4 feature files GREEN: streaming-tags (4 scenarios), streaming-rake (4 scenarios), discrete-tags (3 scenarios), end-by-tag (4 scenarios).

## Requirements Coverage

| Requirement | Covered By | Status |
|-------------|------------|--------|
| TAG-01: Tag registration on transactions | Plans 04-02 streaming-tags + discrete-tags scenarios | GREEN |
| TAG-02: Bulk end-by-tag settlement | Plan 04-04 endByTag implementation, end-by-tag.feature 4 scenarios | GREEN |
| TAG-03: Tag committed totals updated atomically | Plans 04-02/03 stopStream/credit/debit/transfer paths | GREEN |
| TAG-04: Tag aggregate query | Plan 04-02 TagServiceImpl.aggregate, streaming-tags scenarios | GREEN |
| TAG-05: Tag TTL cleanup | Plan 04-04 TagTtlCleanupJob, TagTtlCleanupJobIT 5 tests | GREEN |
| TAG-06: Tag Redis set management (SADD/SREM) | Plan 04-02 RedisStreamRegistry, RedisStreamRegistryTagIT | GREEN |
| RAKE-02: Three-way rake settlement on stopStream | Plan 04-03 StreamingServiceImpl.stopStream, streaming-rake scenarios | GREEN |
| RAKE-03: Rake fields on streaming_transactions | Plan 04-02 V12 migration + entity, plan 04-03 builder wiring | GREEN |
| RAKE-04: DB check constraint chk_str_rake_balanced | Plan 04-03 V12 migration, StreamingTransactionRakeConstraintIT | GREEN |

## Self-Check: PASSED

- [x] TagServiceImpl.endByTag does NOT throw UnsupportedOperationException
- [x] TagServiceImpl.endByTag contains `idempotencyKeyRepository.findByIdempotencyKeyAndOperation(idempotencyKey, "BULK_END_BY_TAG")`
- [x] TagServiceImpl.endByTag contains `IdempotencyKey.builder().idempotencyKey(idempotencyKey).operation("BULK_END_BY_TAG")`
- [x] TagServiceImpl.endByTag contains `streamingService.stopStream(s.streamId(), new StopStreamRequest(false, effectiveReason))`
- [x] TagServiceImpl.endByTag contains status check skipping SETTLED and ERROR
- [x] TagServiceImpl uses `effectiveReason = reason != null ? reason : "end_by_tag"`
- [x] TagTtlCleanupJob.java exists with @Scheduled, @SchedulerLock, DELETE FROM tag_committed_totals WHERE last_activity_at <
- [x] TokenEngineProperties.TagProperties exists with ttlHours=24 and cleanupCron="0 0 3 * * *"
- [x] TagAutoConfiguration.tagService() includes IdempotencyKeyRepository, StreamingService, ObjectMapper, StreamingTransactionRepository
- [x] TagAutoConfiguration.tagTtlCleanupJob() @Bean factory exists
- [x] end-by-tag.feature has no @Pending tags (all 4 scenarios active)
- [x] TagTtlCleanupJobIT exists with 5 tests, all GREEN
- [x] Full suite: 88 tests, 0 failures, 0 errors
- [x] Commits 7d5a7e0, 6987379, bdad2b1 present in git log
