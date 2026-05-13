---
phase: 02-discrete-transactions
plan: "03"
subsystem: engine-service
tags: [cucumber, acceptance-tests, concurrency, rake, metadata, idempotency]
dependency_graph:
  requires: [02-02]
  provides: [discrete-credit.feature, discrete-debit.feature, discrete-metadata.feature, discrete-rake.feature, TransactionSteps, DiscreteTransactionConcurrencyTest]
  affects: []
tech_stack:
  added: []
  patterns: [Cucumber @Given/@When/@Then step reuse, CountDownLatch concurrency gate, @ServiceConnection Testcontainers via Spring @TestConfiguration]
key_files:
  created:
    - engine-service/src/test/resources/features/discrete-credit.feature
    - engine-service/src/test/resources/features/discrete-debit.feature
    - engine-service/src/test/resources/features/discrete-metadata.feature
    - engine-service/src/test/resources/features/discrete-rake.feature
    - engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java
    - engine-service/src/test/java/com/certacota/engine/service/DiscreteTransactionConcurrencyTest.java
  modified:
    - engine-service/src/test/java/com/certacota/engine/service/steps/AccountSteps.java
decisions:
  - "Single-quoted JSON in feature file step text (e.g. '{\"key\":\"value\"}') — Cucumber {string} parameter matches single-quoted strings; unquoted JSON with curly braces is treated as Cucumber expression parameters and fails to resolve"
  - "@Testcontainers removed from DiscreteTransactionConcurrencyTest — project uses spring-boot-testcontainers with @ServiceConnection pattern; org.testcontainers:junit-jupiter is not a declared dependency"
  - "account committed balance assertion added to AccountSteps (not TransactionSteps) — shared step used by both existing and new feature files"
metrics:
  duration: "~10 min"
  completed: "2026-05-13"
  tasks_completed: 2
  files_created: 7
---

# Phase 2 Plan 3: Acceptance Test Suite Summary

**One-liner:** Four Cucumber feature files (13 scenarios covering DTX-01 through DTX-03, META-01, META-02, RAKE-01) plus a JUnit 5 concurrency stress test proving no double-spend under 20 simultaneous debits.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Cucumber feature files and TransactionSteps step definitions | 16370d9 | discrete-credit.feature, discrete-debit.feature, discrete-metadata.feature, discrete-rake.feature, TransactionSteps.java, AccountSteps.java (modified) |
| 2 | Concurrent debit stress test (DTX-04) | 5f26f66 | DiscreteTransactionConcurrencyTest.java |

## Verification Results

- `./gradlew :engine-service:test --tests '*.CucumberTestRunner'`: BUILD SUCCESSFUL — all 13 new scenarios pass
- `./gradlew :engine-service:test --tests '*ConcurrencyTest*'`: BUILD SUCCESSFUL — concurrentDebitsDoNotDoubleSpend PASSED
- `./gradlew :engine-service:test`: BUILD SUCCESSFUL — full suite including Phase 1 scenarios (no regression)
- `./gradlew test` (project root): BUILD SUCCESSFUL — exit code 0

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] JSON metadata in feature file step text requires single-quote wrapping**
- **Found during:** Task 1
- **Issue:** Feature file steps like `with metadata {"key":"value"}` failed with UndefinedStepException — Cucumber parsed `{key}` inside the JSON as a Cucumber expression parameter, preventing `{string}` from matching
- **Fix:** Wrapped all JSON metadata values in single quotes in the feature files: `with metadata '{"key":"value"}'` — Cucumber's `{string}` parameter matches both single and double-quoted strings
- **Files modified:** discrete-credit.feature, discrete-metadata.feature, discrete-rake.feature
- **Commit:** 16370d9

**2. [Rule 3 - Blocking] @Testcontainers annotation unavailable — removed**
- **Found during:** Task 2
- **Issue:** `org.testcontainers.junit.jupiter.Testcontainers` import failed; project only includes `spring-boot-testcontainers` (Spring Boot's @ServiceConnection pattern), not `org.testcontainers:junit-jupiter`
- **Fix:** Removed `@Testcontainers` annotation and its import. The container is managed by `TestcontainersConfiguration` via Spring's `@Import` and `@ServiceConnection` — no JUnit extension annotation needed
- **Files modified:** DiscreteTransactionConcurrencyTest.java
- **Commit:** 5f26f66

**3. [Rule 2 - Missing Step] Added `account {string} has committed balance {bigdecimal}` to AccountSteps**
- **Found during:** Task 1
- **Issue:** Feature files use step "account {string} has committed balance {bigdecimal}" but only "the account {string} exists with committed balance {bigdecimal}" existed in AccountSteps. The plan described it as a shared step but it did not yet exist.
- **Fix:** Added `accountHasCommittedBalance` step to AccountSteps.java using direct repository read (no HTTP round-trip)
- **Files modified:** AccountSteps.java
- **Commit:** 16370d9

## Known Stubs

None — all scenarios have full implementations calling live endpoints.

## Threat Flags

No new security surface introduced. Test-only code; no production endpoints added.

## Self-Check: PASSED

Files verified present:
- engine-service/src/test/resources/features/discrete-credit.feature: FOUND
- engine-service/src/test/resources/features/discrete-debit.feature: FOUND
- engine-service/src/test/resources/features/discrete-metadata.feature: FOUND
- engine-service/src/test/resources/features/discrete-rake.feature: FOUND
- engine-service/src/test/java/com/certacota/engine/service/steps/TransactionSteps.java: FOUND
- engine-service/src/test/java/com/certacota/engine/service/DiscreteTransactionConcurrencyTest.java: FOUND

Commits verified:
- 16370d9: feat(02-03): Cucumber feature files and TransactionSteps step definitions — FOUND
- 5f26f66: feat(02-03): concurrent debit stress test (DTX-04) — FOUND
