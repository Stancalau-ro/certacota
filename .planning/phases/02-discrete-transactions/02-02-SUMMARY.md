---
phase: 02-discrete-transactions
plan: "02"
subsystem: engine-spring, engine-service, engine-core
tags: [service-layer, rake, pessimistic-locking, bean-validation, autoconfigure]
dependency_graph:
  requires: [02-01]
  provides: [TransactionServiceImpl, TransactionController, POST /api/v1/transactions, RakeProperties, TransactionService bean]
  affects: [02-03]
tech_stack:
  added: [jakarta.validation-api compileOnly in engine-core]
  patterns: [PESSIMISTIC_WRITE first-in-method, rake three-way atomic split, idempotency check-first, @ConditionalOnMissingBean service wiring]
key_files:
  created:
    - engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java
    - engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java
  modified:
    - engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java
    - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionRequest.java
    - engine-core/build.gradle
decisions:
  - "findWithLock acquired as first statement in doDebit/doCredit before floor check — eliminates TOCTOU window (T-02-02)"
  - "Rake three-way split uses findWithLock only for from-account (debit); to-account and platform use plain findById (credits) — no lock cycle possible"
  - "RoundingMode.DOWN for all rake BigDecimal.setScale(18) — conservative: platform takes slightly less, never more"
  - "jakarta.validation-api added compileOnly to engine-core so PostTransactionRequest can carry @NotNull/@Positive without pulling in runtime validation impl"
  - "GlobalExceptionHandler already had all four required handlers; no modification needed"
metrics:
  duration: "~8 min"
  completed: "2026-05-13"
  tasks_completed: 2
  files_created: 8
---

# Phase 2 Plan 2: Discrete Transaction Service Layer Summary

**One-liner:** TransactionServiceImpl with idempotent credit/debit/rake three-way split, TransactionController POST /api/v1/transactions, @Valid input rejection, and TransactionService bean wired in autoconfigure.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | TokenEngineProperties RakeProperties + TransactionServiceImpl | 72524e3 | TokenEngineProperties.java, TransactionServiceImpl.java |
| 2 | TransactionController + autoconfigure wiring + @Valid validation | 8d0d819 | TransactionController.java, TokenEngineAutoConfiguration.java, PostTransactionRequest.java, engine-core/build.gradle |

## Verification Results

- `./gradlew :engine-spring:build`: BUILD SUCCESSFUL — TransactionServiceImpl compiles with full credit/debit/rake paths
- `./gradlew :engine-service:build`: BUILD SUCCESSFUL — TransactionController wired, @Valid annotations resolved
- `./gradlew :engine-service:test --rerun`: BUILD SUCCESSFUL — Phase 1 Cucumber suite remains fully green

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Dependency] Added jakarta.validation-api to engine-core**
- **Found during:** Task 2
- **Issue:** PostTransactionRequest lives in engine-core, which had no jakarta.validation-api dependency; adding @NotNull/@Positive without it causes compilation failure
- **Fix:** Added `compileOnly 'jakarta.validation:jakarta.validation-api'` to engine-core/build.gradle
- **Files modified:** engine-core/build.gradle
- **Commit:** 8d0d819

## Threat Flags

No new security surface introduced. All mitigations from the plan threat model addressed:

| Threat ID | Status | Notes |
|-----------|--------|-------|
| T-02-01 | Mitigated | @NotNull @Positive on PostTransactionRequest.amount — negative amounts rejected at controller boundary |
| T-02-02 | Mitigated | findWithLock is first statement in doDebit before floor check — TOCTOU window eliminated |
| T-02-03 | Inherited | metadata stored via @JdbcTypeCode(JSON) parameterized binding (Plan 01) — no interpolation |
| T-02-04 | Mitigated | RoundingMode.DOWN + BigDecimal.setScale(18) on all rake computations |
| T-02-06 | Mitigated | Only from-account uses findWithLock; to-account and platform use plain findById |
| T-02-07 | Accepted by design | Idempotency operation-scoped; same-operation replay returns cached response |

## Known Stubs

None — all code paths are fully implemented and functional.

## Self-Check: PASSED

Files verified present:
- engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java: FOUND
- engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java: FOUND
- engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java: FOUND (modified)
- engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java: FOUND (modified)
- engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionRequest.java: FOUND (modified)

Commits verified:
- 72524e3: feat(02-02): TokenEngineProperties RakeProperties + TransactionServiceImpl — FOUND
- 8d0d819: feat(02-02): TransactionController, autoconfigure wiring, @Valid validation — FOUND
