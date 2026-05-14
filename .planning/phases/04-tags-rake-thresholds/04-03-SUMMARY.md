---
phase: 04
plan: 03
subsystem: streaming-rake
tags: [streaming, rake, three-way-split, settlement, check-constraint, lock-ordering, jpa, cucumber]
dependency_graph:
  requires: [04-02]
  provides: [04-04]
  affects: [engine-core, engine-spring, engine-service]
tech_stack:
  added:
    - chk_str_rake_balanced DB CHECK constraint on streaming_transactions (RAKE-04)
    - STREAMING_RAKE_CREDIT and STREAMING_RAKE_PLATFORM_CREDIT audit log operation codes
    - StreamingTransactionRakeConstraintIT verifying constraint via raw JdbcTemplate
    - postgresql testRuntimeOnly dependency in engine-spring for constraint IT
  patterns:
    - Three-way debit/credit/credit lock order: from-account → to-account → platform-account → tags(alphabetical)
    - DB CHECK constraint as RAKE-04 safety net independent of service arithmetic
    - D-13 Redis→Postgres fallback for rake fields at settlement time
    - toAccountAmount (not settledAmount) used for tag total_credited_recipient (derived totalRaked = totalDebited - totalCreditedRecipient invariant)
key_files:
  created:
    - engine-spring/src/test/java/com/certacota/engine/spring/repository/StreamingTransactionRakeConstraintIT.java
  modified:
    - engine-service/src/main/resources/db/migration/V12__add_streaming_rake_fields.sql
    - engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java
    - engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java
    - engine-service/src/test/resources/features/streaming-rake.feature
    - engine-spring/build.gradle
decisions:
  - "Lock order from→to→platform mirrors TransactionServiceImpl.transfer() exactly, preventing deadlock in concurrent rake stream settlements"
  - "tag total_credited_recipient set to toAccountAmount (not settledAmount) so derived totalRaked = totalDebited - totalCreditedRecipient holds per D-07"
  - "Platform-account lock NOT acquired when rakeAmount=0 (matches transfer() optimization), to-account lock acquired unconditionally when toAccountId present"
  - "D-13 fallback: stopStream reads rake fields from Postgres (findByStreamId) when Redis StreamState lacks them, ensuring settlement correctness after partial Redis failures"
  - "Feature file fixed: srake-full-to account added to full-rake scenario setup (to-account is always locked when toAccountId is non-null)"
metrics:
  duration: "~20 min"
  completed: "2026-05-14T17:20:00Z"
  tasks: 2
  files_created: 1
  files_modified: 5
---

# Phase 4 Plan 3: Streaming Rake Settlement Vertical Slice Summary

Atomic three-way rake settlement in stopStream — DB check constraint enforces RAKE-04; four streaming-rake Cucumber scenarios GREEN; lock ordering from→to→platform→tags(alphabetical) prevents deadlock.

## What Was Built

**Task 1 — V12 migration + check constraint + constraint IT (commit 60356ff)**

V12 migration extended with two additional columns (`to_account_amount`, `rake_amount`) and the `chk_str_rake_balanced` CHECK constraint. The constraint allows NULL on any of the three settlement columns (covers ACTIVE rows and non-rake settlements) but enforces `settled_amount = to_account_amount + rake_amount` when all three are present. `StreamingTransaction` entity gained `toAccountAmount` and `rakeAmount` JPA fields.

`StreamingTransactionRakeConstraintIT` in engine-spring tests the raw DB constraint via a self-contained Postgres Testcontainer + JdbcTemplate (no Flyway, no Spring Boot context). Four tests: balanced insert succeeds, unbalanced insert fails with constraint name, NULL settled_amount allowed, NULL to_account_amount allowed.

**Task 2 — stopStream three-way settlement + streaming-rake.feature GREEN (commit 713d14e)**

`StreamingServiceImpl.stopStream()` extended with:
1. Rake arithmetic: `rakeAmount = clampedAmount × rakeRate` (scale 18, RoundingMode.DOWN); `toAccountAmount = clampedAmount − rakeAmount`
2. Lock acquisition per D-11: to-account (when `toAccountId != null`), then platform-account (when `platformAccountId != null AND rakeAmount > 0`)
3. Credits: `toAccount.credit(toAccountAmount)` and `platformAccount.credit(rakeAmount)` with `STREAMING_RAKE_CREDIT` / `STREAMING_RAKE_PLATFORM_CREDIT` audit log entries
4. Settled row: `.toAccountAmount(...)` and `.rakeAmount(...)` set when `toAccountId` present, null for non-rake streams (constraint stays quiet)
5. Tag totals fix: `totals.addCreditedRecipient(toAccountAmount)` instead of `clampedAmount`, so `totalRaked = totalDebited − totalCreditedRecipient` invariant holds for rake streams
6. D-13 fallback: if Redis StreamState lacks rake fields but DB row has them, DB values preferred

`startStream()` now includes `.toAccountId()`, `.rakeRate()`, `.platformAccountId()` in the `StreamingTransaction` builder.

All four `streaming-rake.feature` scenarios activated (removed `@Pending`): hybrid rake (10%), zero-rake, full-rake (100%), non-rake. All GREEN.

## Newly-Green Streaming-Rake Scenarios

| Scenario | Result |
|----------|--------|
| Rake-enabled streaming settlement produces three-way split (hybrid 10%) | PASSED |
| Zero rake rate credits full settled amount to recipient | PASSED |
| Full rake routes entire settlement to platform account | PASSED |
| Non-rake stream settlement credits full amount to from-account drain only | PASSED |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Feature file missing srake-full-to account setup**
- **Found during:** Task 2 — full-rake scenario references `srake-full-to` as toAccountId but the account was not created in the Given steps; stopStream acquires the to-account lock unconditionally when toAccountId is non-null, causing AccountNotFoundException
- **Fix:** Added `Given no account with id "srake-full-to" exists` and `And an account "srake-full-to" exists with balance 0.00` steps to the full-rake scenario
- **Files modified:** `engine-service/src/test/resources/features/streaming-rake.feature`
- **Commit:** 713d14e

**2. [Rule 3 - Blocking] PostgreSQL does not support `ADD CONSTRAINT IF NOT EXISTS`**
- **Found during:** Task 1 — initial constraint IT used `ADD CONSTRAINT IF NOT EXISTS` which is not valid Postgres DDL (only `ADD COLUMN IF NOT EXISTS` is supported)
- **Fix:** Changed to `ADD CONSTRAINT chk_str_rake_balanced` (no IF NOT EXISTS) in the test setUp; the V12 migration already used the correct form
- **Files modified:** `StreamingTransactionRakeConstraintIT.java`
- **Commit:** 60356ff

**3. [Rule 2 - Missing] Engine-spring testRuntimeOnly PostgreSQL driver**
- **Found during:** Task 1 — StreamingTransactionRakeConstraintIT uses `DriverManagerDataSource` with `org.postgresql.Driver` but engine-spring build.gradle had no PostgreSQL driver on the test classpath
- **Fix:** Added `testRuntimeOnly 'org.postgresql:postgresql'` to engine-spring/build.gradle
- **Files modified:** `engine-spring/build.gradle`
- **Commit:** 60356ff

## Known Stubs

None. All rake settlement paths are fully implemented. Non-rake streams continue to work unchanged (all pre-existing streaming feature scenarios GREEN with no regression).

## Forward Dependency for Plan 04

Plan 04 (end-by-tag bulk settlement) reuses the same `stopStream()` rake path. When `TagServiceImpl.endByTag()` iterates streams tagged with a given tag and calls `stopStream()` for each, the three-way rake split executes automatically — no additional rake logic needed in plan 04. The `total_credited_recipient` fix in this plan also ensures that bulk end-by-tag tag aggregate queries will correctly show `totalRaked = totalDebited − totalCreditedRecipient` for rake-enabled streams settled via bulk termination.

## Self-Check: PASSED

- [x] V12__add_streaming_rake_fields.sql contains to_account_amount and rake_amount columns
- [x] V12__add_streaming_rake_fields.sql contains chk_str_rake_balanced CHECK constraint
- [x] StreamingTransaction.java has toAccountAmount and rakeAmount fields
- [x] StreamingTransactionRakeConstraintIT.java exists and passes (4/4 tests)
- [x] StreamingServiceImpl.stopStream contains rake arithmetic (rakeAmount, toAccountAmount)
- [x] StreamingServiceImpl.stopStream acquires to-account and platform-account locks
- [x] streaming-rake.feature has no @Pending tags (all 4 scenarios active)
- [x] Commits 60356ff (Task 1) and 713d14e (Task 2) present in git log
- [x] ./gradlew test BUILD SUCCESSFUL (54 Cucumber scenarios, 4 constraint IT tests all pass)
