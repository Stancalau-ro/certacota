---
phase: 2
slug: discrete-transactions
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-13
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Cucumber 7.22.1 + JUnit 5 Platform + Testcontainers 1.21.3 |
| **Config file** | Inherits Phase 1 — `CucumberTestRunner`, `CucumberSpringConfiguration`, `TestcontainersConfiguration` |
| **Quick run command** | `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i` |
| **Full suite command** | `./gradlew :engine-service:test` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i`
- **After every plan wave:** Run `./gradlew test` (all three modules)
- **Before `/gsd-verify-work`:** Full suite must be green (including concurrency test)
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01 | 01 | 1 | DTX-01 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 02-02 | 01 | 1 | DTX-02 | Negative amount | `@Positive` on amount, `CHECK (amount > 0)` | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 02-03 | 01 | 1 | DTX-03 | Concurrent double-spend | `PESSIMISTIC_WRITE` lock serializes balance mutations | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 02-04 | 01 | 1 | DTX-04 | Concurrent double-spend | `PESSIMISTIC_WRITE` lock serializes balance mutations | JUnit 5 `@SpringBootTest` | `./gradlew :engine-service:test --tests "*ConcurrencyTest*"` | ❌ W0 | ⬜ pending |
| 02-05 | 01 | 1 | META-01 | Metadata injection | `@JdbcTypeCode` parameterized binding — no SQL interpolation | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 02-06 | 01 | 1 | META-02 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 02-07 | 01 | 1 | RAKE-01 | Rake arithmetic overflow | `NUMERIC(38,18)` precision; `BigDecimal.setScale(18, RoundingMode.DOWN)` | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `engine-service/src/test/resources/features/discrete-credit.feature` — stubs for DTX-01, META-01
- [ ] `engine-service/src/test/resources/features/discrete-debit.feature` — stubs for DTX-02, DTX-03
- [ ] `engine-service/src/test/resources/features/discrete-metadata.feature` — stubs for META-02
- [ ] `engine-service/src/test/resources/features/discrete-rake.feature` — stubs for RAKE-01
- [ ] `engine-service/src/test/java/.../steps/TransactionSteps.java` — step definitions for the above features
- [ ] `engine-service/src/test/java/.../DiscreteTransactionConcurrencyTest.java` — DTX-04 stress test (20 concurrent debits, `ExecutorService` + `CountDownLatch`)
- [ ] `engine-service/src/main/resources/db/migration/V4__create_discrete_transactions.sql` — schema exists before test infrastructure exercises transactions
- [ ] `engine-service/src/main/resources/db/migration/V5__add_transaction_id_to_audit_log.sql` — audit log FK added before META-02 tests

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Three-way rake balance invariant (debit = sum of credits) in live Postgres | RAKE-01 | DB check constraint validation requires live DB | Start service, POST rake-enabled transaction, query `accounts` table for from/to/platform balances; verify debit equals sum of credits |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
