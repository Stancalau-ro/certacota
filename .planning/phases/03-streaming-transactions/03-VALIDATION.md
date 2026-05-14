---
phase: 3
slug: streaming-transactions
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-14
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Cucumber 7.22.1 + JUnit 5 Platform + Testcontainers 1.21.3 (unchanged from Phase 2) |
| **Config file** | Inherits from Phase 1/2 — `CucumberTestRunner`, `CucumberSpringConfiguration`, `TestcontainersConfiguration` all exist |
| **Quick run command** | `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i` |
| **Full suite command** | `./gradlew test` (all three modules) |
| **Estimated runtime** | ~90 seconds (includes Redis + Postgres Testcontainers startup) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i`
- **After every plan wave:** Run `./gradlew test` (all three modules)
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 3-01-01 | 01 | 1 | STR-01 | T-3-01 | stream start rejected on CLOSED account (409) | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-01-02 | 01 | 1 | STR-02 | T-3-03 | settle clamped to `min(projected, available-floor)` | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-01-03 | 01 | 1 | STR-03 | — | estimated balance without DB read per query | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-01-04 | 01 | 1 | BAL-02 | — | `estimatedBalance`, `estimatedAt`, `estimatedDrainAt` correct | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-02-01 | 02 | 2 | STR-07 | — | `minimumAmount` enforced; `ignoreMinimum=true` waives | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-02-02 | 02 | 2 | STR-08 | — | increment billing: `floor(rate × elapsed / increment) × increment` | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-02-03 | 02 | 2 | STR-09 | — | auto-termination fires when `remainingBalance < increment` | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-02-04 | 02 | 2 | STR-06 | — | no floating-point in arithmetic path; BigDecimal throughout | Unit test | `./gradlew :engine-spring:test` | ❌ W0 | ⬜ pending |
| 3-03-01 | 03 | 3 | AUTO-01 | — | auto-terminate at floor; settle actual elapsed (min waived) | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-03-02 | 03 | 3 | AUTO-02 | — | scheduler reschedules on balance change; Redisson enqueue verified | Integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-03-03 | 03 | 3 | AUTO-03 | — | `reason = "balance_exhaustion"` distinguishable in audit log | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 3-04-01 | 04 | 4 | STR-04 | T-3-02 | concurrent streaming + discrete: no double-spend, no lost update | JUnit 5 `@SpringBootTest` | `./gradlew :engine-service:test --tests "*StreamingConcurrencyTest*"` | ❌ W0 | ⬜ pending |
| 3-04-02 | 04 | 4 | STR-05 | — | atomic settlement; Redis and Postgres never permanently divergent | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql` — streaming_transactions table
- [ ] `engine-service/src/main/resources/db/migration/V8__create_shedlock.sql` — shedlock table for scheduler guards
- [ ] `engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql` — audit_archive schema + table
- [ ] `engine-service/src/test/java/.../TestcontainersConfiguration.java` — MODIFY: add Redis `GenericContainer` with `@DynamicPropertySource`
- [ ] `engine-service/src/test/resources/features/streaming-start.feature` — covers STR-01
- [ ] `engine-service/src/test/resources/features/streaming-stop.feature` — covers STR-02, STR-05
- [ ] `engine-service/src/test/resources/features/streaming-estimation.feature` — covers STR-03, BAL-02
- [ ] `engine-service/src/test/resources/features/streaming-minimum-amount.feature` — covers STR-07
- [ ] `engine-service/src/test/resources/features/streaming-increment.feature` — covers STR-08, STR-09
- [ ] `engine-service/src/test/resources/features/streaming-auto-termination.feature` — covers AUTO-01, AUTO-02, AUTO-03
- [ ] `engine-service/src/test/java/.../steps/StreamingSteps.java` — step definitions for all streaming features
- [ ] `engine-service/src/test/java/.../StreamingConcurrencyTest.java` — covers STR-04
- [ ] `engine-spring/src/test/java/.../ArithmeticTest.java` — covers STR-06 (BigDecimal arithmetic, no Spring context)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Redis failover (Sentinel) triggers 503 responses | D-29, D-30 | Cannot reliably simulate Redis outage + Sentinel failover in Testcontainers without dedicated Redis Sentinel container setup | Stop the Redis container mid-test; verify POST /streams returns 503; verify discrete debit with active streams returns 503 |
| OPS-02 archival job runs on schedule and deletes old partitions | D-36, D-39 | Time-based partition archival requires 90-day elapsed rows; impractical in automated tests | Insert backdated rows with `recorded_at = NOW() - INTERVAL '91 days'`; trigger archival job manually; verify rows moved to audit_archive schema |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
