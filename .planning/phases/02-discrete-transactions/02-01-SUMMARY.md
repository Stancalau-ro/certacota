---
phase: 02-discrete-transactions
plan: "01"
subsystem: engine-core, engine-service
tags: [domain, flyway, jpa, contracts]
dependency_graph:
  requires: [01-01, 01-02, 01-03]
  provides: [discrete_transactions DDL, TransactionService interface, PostTransactionRequest, PostTransactionResponse, DiscreteTransaction entity, AccountRepository.findWithLock]
  affects: [02-02, 02-03]
tech_stack:
  added: []
  patterns: [PESSIMISTIC_WRITE locking, append-only ledger entity, nullable FK for phased migration]
key_files:
  created:
    - engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql
    - engine-service/src/main/resources/db/migration/V5__add_transaction_id_to_audit_log.sql
    - engine-core/src/main/java/com/certacota/engine/core/domain/TransactionType.java
    - engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java
    - engine-core/src/main/java/com/certacota/engine/core/repository/DiscreteTransactionRepository.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionRequest.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionResponse.java
    - engine-core/src/main/java/com/certacota/engine/core/service/TransactionService.java
  modified:
    - engine-core/src/main/java/com/certacota/engine/core/domain/BalanceAuditLog.java
    - engine-core/src/main/java/com/certacota/engine/core/repository/AccountRepository.java
decisions:
  - "V5 transaction_id column is nullable — Phase 1 audit entries (account creation) have no associated transaction"
  - "AccountRepository.findWithLock uses PESSIMISTIC_WRITE lock via JPQL for SELECT FOR UPDATE semantics; must be called inside @Transactional (enforced in Plan 02)"
  - "DiscreteTransaction all columns updatable=false — transactions are append-only ledger entries, never modified after insert"
  - "PostTransactionResponse.type is String (txn.getType().name()) not enum — API surface remains stable if internal enum names change"
metrics:
  duration: "~10 min"
  completed: "2026-05-13"
  tasks_completed: 2
  files_created: 10
---

# Phase 2 Plan 1: Discrete Transactions Domain Foundation Summary

**One-liner:** Flyway V4/V5 DDL plus eight engine-core contracts (entity, enum, repos, DTOs, service interface) establishing the contracts-first foundation for Plan 02's TransactionServiceImpl.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Flyway migrations V4 and V5 | 60b22ac | V4__create_discrete_transactions.sql, V5__add_transaction_id_to_audit_log.sql |
| 2 | engine-core domain contracts | 125a222 | TransactionType, DiscreteTransaction, BalanceAuditLog (mod), AccountRepository (mod), DiscreteTransactionRepository, PostTransactionRequest, PostTransactionResponse, TransactionService |

## Verification Results

- `./gradlew :engine-service:test` (Cucumber): BUILD SUCCESSFUL — Flyway applied 5 migrations (V1-V5) cleanly
- `./gradlew :engine-core:build`: BUILD SUCCESSFUL — all 8 new/modified files compile with no errors
- Phase 1 Cucumber scenarios remain fully green (V5 nullable column is non-breaking)

## Deviations from Plan

None — plan executed exactly as written.

## Threat Flags

No new security surface introduced. All mitigations from the plan threat model are addressed:
- T-02-01: CHECK (amount > 0) present in V4 DDL
- T-02-02: @Lock(PESSIMISTIC_WRITE) on findWithLock — serializes concurrent writers
- T-02-03: @JdbcTypeCode(SqlTypes.JSON) on DiscreteTransaction.metadata — parameterized binding, no interpolation
- T-02-04: NUMERIC(38,18) in V4 DDL for overflow protection
- T-02-05: idempotency_key column present (nullable, operation-scoped)

## Known Stubs

None — this plan is contracts-only (DDL + interfaces + entity). No business logic stubs exist. TransactionService interface methods are intentionally unimplemented; implementation is the subject of Plan 02.

## Self-Check: PASSED

Files verified present:
- engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql: FOUND
- engine-service/src/main/resources/db/migration/V5__add_transaction_id_to_audit_log.sql: FOUND
- engine-core/src/main/java/com/certacota/engine/core/domain/TransactionType.java: FOUND
- engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java: FOUND
- engine-core/src/main/java/com/certacota/engine/core/repository/DiscreteTransactionRepository.java: FOUND
- engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionRequest.java: FOUND
- engine-core/src/main/java/com/certacota/engine/core/dto/PostTransactionResponse.java: FOUND
- engine-core/src/main/java/com/certacota/engine/core/service/TransactionService.java: FOUND

Commits verified:
- 60b22ac: feat(02-01): Flyway migrations V4 and V5 — FOUND
- 125a222: feat(02-01): engine-core domain contracts — FOUND
